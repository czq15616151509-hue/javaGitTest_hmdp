package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @功能：秒杀优惠卷
 * @说明：本项目本身不是一个完整的项目, 只是参考了部分代码，用于学习，非用于商业用途。比如只有生成秒杀订单的代码，没有生成优惠券的代码。
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;   //操作订单表，订单信息

    //秒杀优惠券 （而且实现一人一单）
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) { //请求参数是优惠卷id，返回参数是订单id
        //异步秒杀
        return voucherOrderService.seckillVoucherAsync(voucherId);

//        //非异步
//        return voucherOrderService.seckillVoucher(voucherId);
    }
}
