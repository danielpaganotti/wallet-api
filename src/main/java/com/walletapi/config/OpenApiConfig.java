package com.walletapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "ApiKeyAuth";

    @Bean
    public OpenAPI walletApiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet API")
                        .description("Digital wallet backend: create wallets, register credit/debit " +
                                "operations and query the statement.")
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME,
                        new SecurityScheme()
                                .name("X-API-Key")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)));
    }
}
