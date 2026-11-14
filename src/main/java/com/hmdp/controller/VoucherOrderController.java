package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /*
    购买秒杀优惠券
     */
    @PostMapping("seckill/{id}")
    public Result buySeckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.buySeckillVoucher(voucherId);
    }

    /**
     * 支付渠道回调入口；本地联调也可以直接调它模拟「支付成功」。
     *
     * TODO 真实渠道接入时补：验签、校验金额与 out_trade_no 归属、
     *      渠道流水号去重、notify 的固定应答格式。
     *
     * 本地状态流转（CAS 1→2、履约、异常走退款补偿）已在
     * VoucherOrderServiceImpl.onPaySuccess 实现。
     */
    @PostMapping("/pay/notify")
    public Result payNotify(@RequestParam("orderId") Long orderId) {
        return voucherOrderService.onPaySuccess(orderId);
    }
}
