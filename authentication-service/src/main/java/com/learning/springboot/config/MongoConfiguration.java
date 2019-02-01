package com.learning.springboot.config;

import com.learning.springboot.repository.AuthenticationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

@ConditionalOnProperty(prefix = "configuration", name = "mongo", havingValue = "true", matchIfMissing = true)
@Configuration
@EnableMongoAuditing
@EnableReactiveMongoRepositories(basePackageClasses = AuthenticationRepository.class)
public class MongoConfiguration {
}
