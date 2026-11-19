package com.hmdp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 支付渠道网关占位：所有真实第三方调用都留 TODO，本地返回模拟值，
 * 让「下单 → 多轮检查 → 超时关单」整条链路可以先跑通。
 *
 * query  -> 微信 queryorder / 支付宝 alipay.trade.query
 * close  -> 微信 closeorder  / 支付宝 alipay.trade.close
 * refund -> 渠道退款接口
 *
 * 接入真实渠道时只替换方法体，上层流程不用改。
 */
@Slf4j
@Component
public class PayGateway {

    public static final String SUCCESS = "SUCCESS";
    public static final String NOTPAY = "NOTPAY";
    public static final String PAYING = "USERPAYING";

    /** TODO 调渠道查单接口，返回 SUCCESS / NOTPAY / USERPAYING */
    public String query(Long orderId) {
        log.info("[TODO 渠道查单] orderId={}，本地模拟返回 NOTPAY", orderId);
        return NOTPAY;
    }

    /** TODO 调渠道关闭交易单，封死付款入口；返回 false 时不得关本地订单 */
    public boolean close(Long orderId) {
        log.info("[TODO 渠道关单] orderId={}，本地模拟返回成功", orderId);
        return true;
    }

    /** TODO 调渠道退款：关单后回调才到、或重复回调时走这里 */
    public void refund(Long orderId) {
        log.info("[TODO 渠道退款] orderId={}", orderId);
    }
}
