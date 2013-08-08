package com.rackspace.papi.service.httpconnectionpool;

import com.rackspace.papi.service.httpconnectionpool.config.HttpConnectionPoolConfig;
import com.rackspace.papi.service.httpconnectionpool.config.PoolType;
import com.rackspace.papi.service.httpconnectionpool.HttpConnectionPoolService;
import org.apache.http.client.HttpClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class HttpConnectionPoolServiceImpl implements HttpConnectionPoolService<HttpConnectionPoolConfig> {

    Map<String, HttpClient> poolMap;
    String defaultClientId;

    public HttpConnectionPoolServiceImpl(HttpConnectionPoolConfig conf) {

        poolMap = new HashMap<String, HttpClient>();

        for (PoolType poolType : conf.getPool()) {
            if (poolType.isDefault()) {
                defaultClientId = poolType.getId();
            }
            poolMap.put(poolType.getId(), HttpConnectionPoolProvider.genClient(poolType));
        }
    }

    @Override
    public HttpClient getClient(String id) {
        if (id == null || id.isEmpty()) {
            return poolMap.get(defaultClientId);
        } else {
            return poolMap.get(id);
        }
    }

    @Override
    public void configure(HttpConnectionPoolConfig config) {
        throw new UnsupportedOperationException("implement me");
    }

    @Override
    public boolean isAvailable(String poolName) {
        return poolMap.containsKey(poolName);
    }

    @Override
    public Set<String> getAvailablePools() {
        return poolMap.keySet();
    }

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException("implement me");
    }
}
