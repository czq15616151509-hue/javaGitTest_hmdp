package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 查询所有商铺类型（采用redis缓存）
    @Override
    public Result queryShopType() {
        //查询redis缓存
        List<String> shopTypeStr = stringRedisTemplate.opsForList().range("cache:shop:type", 0, -1);
        //判断是否存在，存在就返回,返回之前要把数据转换成shopType对象，而且每个元素要按照sort字段进行升序排序
        if (!shopTypeStr.isEmpty()) {
            List<ShopType> shopTypeList = shopTypeStr.stream()
                    .map(shopTypeString -> JSONUtil.toBean(shopTypeString, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(shopTypeList);
        }
        //不存在就查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //数据库是否存在，不存在就返回错误提示
        if (shopTypeList.isEmpty()) {
            return Result.fail("没有查询到商铺类型");
        }
        //存在就缓存到redis，存之前要把数据转换成json字符串类型
        List<String> jsonStrings = shopTypeList.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().leftPushAll("cache:shop:type", jsonStrings);
        //返回结果
        return Result.ok(shopTypeList);
    }
}
