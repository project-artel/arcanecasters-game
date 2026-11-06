package com.wordonline.server.qa;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("artel.qa")
public record QaMatchProperties(Set<Long> accounts, long botId) {
    public QaMatchProperties {
        accounts = accounts == null ? Set.of() : Set.copyOf(accounts);
    }

    public boolean allows(Long id) {
        return id != null && id > 0 && accounts.contains(id);
    }
}
