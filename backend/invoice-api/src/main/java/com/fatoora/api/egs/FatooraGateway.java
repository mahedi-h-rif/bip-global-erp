package com.fatoora.api.egs;

import com.fatoora.zatca.ZatcaEnvironment;
import com.fatoora.zatca.api.FatooraClient;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** One {@link FatooraClient} per environment. Tests replace this bean to avoid network calls. */
@Component
public class FatooraGateway {

    private final Map<ZatcaEnvironment, FatooraClient> clients = new EnumMap<>(ZatcaEnvironment.class);

    public synchronized FatooraClient client(ZatcaEnvironment environment) {
        return clients.computeIfAbsent(environment, FatooraClient::new);
    }
}
