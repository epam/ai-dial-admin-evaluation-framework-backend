package com.epam.aidial.evaluation.configuration.datasource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.Credentials;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DynamicPasswordHikariDataSource extends HikariDataSource {

    private final Supplier<String> passwordProvider;

    @Override
    public Credentials getCredentials() {
        return Credentials.of(getUsername(), getPassword());
    }

    @Override
    public String getPassword() {
        return passwordProvider.get();
    }
}
