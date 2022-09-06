package com.hmdp.config;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/***
 #Create by LCK on 2022/8/30
 # 用法: Redisson配置类
 */
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        //配置累
        Config config = new Config();
        //添加Redis地址，这里添加单点的地址，也可以使用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://47.101.44.131:6379");
        //创建客户端
        return Redisson.create(config);
    }
}
