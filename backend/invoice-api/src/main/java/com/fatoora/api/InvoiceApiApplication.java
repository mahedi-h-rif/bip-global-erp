package com.fatoora.api;

import com.fatoora.api.config.FatooraProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneId;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({FatooraProperties.class, com.fatoora.api.ocr.OcrProperties.class})
public class InvoiceApiApplication {

    /** Invoices are issued in Saudi local time (UTC+3, no DST). */
    public static final ZoneId KSA = ZoneId.of("Asia/Riyadh");

    public static void main(String[] args) {
        SpringApplication.run(InvoiceApiApplication.class, args);
    }
}
