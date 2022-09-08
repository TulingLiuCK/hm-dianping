package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private Logger logger = LoggerFactory.getLogger(VoucherOrderServiceImpl.class);
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHanlder());
    }

    private class VoucherOrderHanlder implements Runnable {
        private final String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 0.初始化stream
                    initStream();
                    //   > 表示最近一条未消费的信息
                    //1获取消息队列中的订单信息 XREADGROUP GROUP G1 C1  COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //2.1如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //4ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", record.getId());
                } catch (Exception e) {
                    logger.error("处理订单异常", e);
                    //如果出了异常，说明没有被ACK确认
                    handlePendingList();
                }
            }
        }
        public void initStream(){
            Boolean exists = stringRedisTemplate.hasKey(queueName);
            if (BooleanUtil.isFalse(exists)) {
                logger.info("stream不存在，开始创建stream");
                // 不存在，需要创建
                stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                logger.info("stream和group创建完毕");
                return;
            }
            // stream存在，判断group是否存在
            StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(queueName);
            if(groups.isEmpty()){
                logger.info("group不存在，开始创建group");
                // group不存在，创建group
                stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                logger.info("group创建完毕");
            }
        }
        private void handlePendingList() {
            while (true) {
                try { //> 表示最近一条未消费的信息
                    //1获取pending-list的订单信息 XREADGROUP GROUP G1 C1  COUNT 1  STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //2.1如果获取失败，说明pending-list没有消息，结束循环
                        break;
                    }
                    //3解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //4ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", record.getId());
                } catch (Exception e) {
                    logger.error("处理pending-list异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }

                }
            }

        }
    }

    //创建阻塞队列
   /* private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHanlder implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //1获取队列中的订单信息
                    System.out.println("进入了异步线程");
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    logger.error("处理订单异常", e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1获取用户
        Long userId = voucherOrder.getUserId();
        //2创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            //获取锁失败
            logger.error("不允许重复下单");
            return;
        }
        try {
            createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }

    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户ID
        Long userId = UserHolder.getUser().getId();
        //获取订单ID
        long orderId = redisIdWorker.nextId("order");
        //1执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //3获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3 返回订单id
        return Result.ok(orderId);
    }





    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5一人一单
        Long userId = voucherOrder.getUserId();
        //5.1查询订单

        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //6.2判断是否存在
        if (count > 0) {
            //用户已经购买过了 不允许下单
            logger.error("用户已经购买过了，每个用户只能购买一次");
            return;
        }
        //6扣减库存
        boolean success = seckillVoucherService.update().
                setSql("stock = stock - 1").   //set stock = stock -1
                        eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
        if (!success) {
            logger.error("库存不足");
        }
        save(voucherOrder);
    }

     /*

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2.为0 ，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6.放入阻塞队列
        orderTasks.add(voucherOrder);
        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy()
        // 4.返回订单id
        return Result.ok(orderId);
    }*/
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单");
        }
        try {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }*/


    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();

        synchronized (userId.toString().intern()) {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                return Result.fail("用户已经购买过一次！");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                return Result.fail("库存不足！");
            }

            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2.用户id
            voucherOrder.setUserId(userId);
            // 7.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 7.返回订单id
            return Result.ok(orderId);
        }
    }*/
}
