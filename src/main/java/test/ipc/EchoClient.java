package test.ipc;

import com.cqyuanye.ipc.RPC;

import java.net.InetSocketAddress;

/**
 * Created by Kali on 14-8-9.
 */
public class EchoClient {
    public static void main(String[] args) {
        Echo echo = (Echo) RPC.getProxy(Echo.class,new InetSocketAddress("localhost",8089));
        System.out.println(echo.echo("Hello world"));
    }
}
