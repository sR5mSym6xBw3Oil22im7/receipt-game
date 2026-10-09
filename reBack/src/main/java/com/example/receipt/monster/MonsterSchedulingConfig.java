package com.example.receipt.monster;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 下書きのカードと期限切れのルームを定期的に片付ける。 */
@Configuration
@EnableScheduling
public class MonsterSchedulingConfig {
}
