package com.example.hotel_back.Pojo.VO;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CustomerStatVO {
    private String month;
    private Long newCustomers;
    private Long returningCustomers;
    private BigDecimal averageStay;
    private BigDecimal averageOrderAmount;
}
