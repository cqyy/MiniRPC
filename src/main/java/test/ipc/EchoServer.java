package test.ipc;

import com.cqyuanye.ipc.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Created by Kali on 14-8-9.
 */
public class EchoServer implements Echo {
    @Override
    public String echo(String msg) {
        return "reply from server: " + msg;
    }

    public static void main(String[] args) throws IOException {
        RPC.Server server = RPC.getServer(new EchoServer(),new InetSocketAddress("localhost",8089));
        server.start();
    }
}
