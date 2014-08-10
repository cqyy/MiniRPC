package com.cqyuanye.ipc;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Kali on 14-8-6.
 */
public class RPC {

    static ClientCache clientCache = new ClientCache();

    private static class ClientCache{
        private final Map<InetSocketAddress,Client> clients = new HashMap<>();

        public Client getClient(InetSocketAddress address){
            Client client = clients.get(address);
            if (client == null){
                client = new Client();
                clients.put(address,client);
            }
            return client;
        }
    }

    private static class Invocator implements InvocationHandler{

        private final Client client;
        private final InetSocketAddress address;

        public Invocator(InetSocketAddress address){
            this.address = address;
            this.client = clientCache.getClient(address);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Invocation invocation = new Invocation(method.getName(),args);
            Object value = client.call(address,invocation);
            return value;
        }
    }

    private static class Invocation implements Serializable{
        public final String method;
        public final Object[] params;
        public final Class[] paramClasses;

        public Invocation(String method,Object[] params){
            this.method = method;
            this.params = params;
            paramClasses = new Class[params.length];
            for(int i = 0; i < paramClasses.length; i++){
                paramClasses[i] = params[i].getClass();
            }
        }
    }

    public static class Server extends com.cqyuanye.ipc.Server {
        private final Object instance;
        private final Class<?> clazz;

        public Server(InetSocketAddress address,Object instance) throws IOException {
            super(address);
            this.instance = instance;
            clazz = instance.getClass();
        }

        @Override
        Object call(Object param) throws Throwable {
            Invocation invocation =  (Invocation)param;
            Method method = clazz.getMethod(invocation.method,((Invocation) param).paramClasses);
            method.setAccessible(true);
            Object value = method.invoke(instance,invocation.params);
            return value;
        }
    }

    public static Server getServer(Object instance,InetSocketAddress address) throws IOException {
        return new Server(address,instance);
    }

    public static Object getProxy(Class<?> protocol,InetSocketAddress address){
        return Proxy.newProxyInstance(
                protocol.getClassLoader(),
                new Class[]{protocol},
                new Invocator(address));
    }
}
