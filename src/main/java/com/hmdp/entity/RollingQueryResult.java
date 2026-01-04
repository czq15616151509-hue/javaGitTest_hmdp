package com.hmdp.entity;

import lombok.Data;

import java.util.List;

/**
 * @功能： 封装朋友圈的滚动分页查询结果；不能使用传统分页查询（数据的更新会导致分页查询结果出错），不能按照下标查询，选择按照score查询（时间戳）
 * @author chen
 * @function
 * @date 2025/10/26
 */
@Data
public class RollingQueryResult {
    private List<?> list;  //滚动分页查询得到的笔记
    private Long minTime;  //上一次滚动查询到的最小时间戳score
    private Integer offset;  //偏移量（距离第一个笔记的便宜位置）
}
