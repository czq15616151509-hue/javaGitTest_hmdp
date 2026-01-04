package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author chen
 * @function
 * @date 2025/10/21
 */

@Configuration
public class RedissionConfig {
    @Bean  //第三方的类交给spring管理
    public RedissonClient RedissionClient(){
        //创建一个配置对象
        Config config = new Config();
        //添加redis地址，这里添加的是单个redis节点的地址；如果采用了集群，则用useClusterServers()方法添加集群地址
        config.useSingleServer().setAddress("redis://192.168.100.128:6379");
        //创建一个Redisson客户端对象
        return Redisson.create(config);
    }
}
