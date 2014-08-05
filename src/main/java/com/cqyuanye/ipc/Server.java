package com.cqyuanye.ipc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;

/**
 * Created by Kali on 14-8-4.
 */
class Server {

    private class Connection{
        private final SocketChannel channel;
        private final LinkedList<Call> calls = new LinkedList<>();

        public Connection(SocketChannel channel){
            this.channel = channel;
        }

        public void send(Call call){
            if (!calls.isEmpty()){
                calls.addLast(call);
            }
            call = calls.getFirst();
            try {
                channel.write(call.response);
                if (call.response.hasRemaining()){
                    calls.addFirst(call);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

    }

    private class Call{
        private int index;
        private Class[] paramClass;
        private Object[] params;
        private ByteBuffer response;
    }

    private class Listener extends Thread{}

    private class Receiver extends Thread{}

    private class Handler extends Thread{}

    private class Responer extends Thread{}

}
