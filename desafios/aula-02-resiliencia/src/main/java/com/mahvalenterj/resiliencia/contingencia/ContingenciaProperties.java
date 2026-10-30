package com.mahvalenterj.resiliencia.contingencia;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contingencia")
public record ContingenciaProperties(int capacidade) {
}
