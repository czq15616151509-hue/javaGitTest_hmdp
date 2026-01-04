package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucherAsync(Long voucherId);
    void subStockAndCreateOrderAsyncToMysql(VoucherOrder voucherOrder);


    //非异步处理
//    Result seckillVoucher(Long voucherId);
//    Result subStockAndCreateOrder(VoucherOrder voucherId);
}
