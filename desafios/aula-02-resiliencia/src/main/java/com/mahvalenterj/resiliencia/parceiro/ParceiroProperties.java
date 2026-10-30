package com.mahvalenterj.resiliencia.parceiro;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Endereço e tempos-limite da chamada ao parceiro de pagamentos. */
@ConfigurationProperties(prefix = "parceiro")
public record ParceiroProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
