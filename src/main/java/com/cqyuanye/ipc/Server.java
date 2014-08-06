package com.cqyuanye.ipc;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * Created by Kali on 14-8-4.
 */
abstract class Server {

    private final Queue<Call> callQueue = new ConcurrentLinkedQueue<>();
    private final List<Connection> connections = new LinkedList<>();

    private class Connection{
        private final SocketChannel channel;
        final LinkedList<Call> calls = new LinkedList<>();

        public Connection(SocketChannel channel){
            this.channel = channel;
        }

        public void readOneRPC(){
            ByteBuffer indexBuf = ByteBuffer.allocate(8);       //int * 2
            try {
                channel.read(indexBuf);
                if (indexBuf.hasRemaining()){
                    throw new IOException("Read index error");
                }
            } catch (IOException e) {
                e.printStackTrace();
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
                return;
            }
            indexBuf.flip();
            int index =indexBuf.getInt();
            int objLen = indexBuf.getInt();
            ByteBuffer buffer = ByteBuffer.allocate(objLen);
            while (buffer.hasRemaining()){
                try {
                    channel.read(buffer);
                } catch (IOException e) {
                    e.printStackTrace();
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                    }
                    return;
                }
            }
            try {
                String json = new String(buffer.array(),"utf-8");
                Gson gson = new Gson();
                Object param = gson.fromJson(json, Object.class);
                Call call = new Call(index,param,this);
                callQueue.offer(call);
                callQueue.notifyAll();
            } catch (UnsupportedEncodingException e) {
                throw new Error("Encoding of client does not match with the server");
            }
        }



    }

    private class Call{
        final int index;
        final Object param;
        final Connection connection;
        private ByteBuffer response;

        public Call(int index,Object param,Connection connection){
            this.index = index;
            this.param = param;
            this.connection = connection;
        }
    }

    private class Listener extends Thread{

        private volatile boolean shouldRun = true;

        private final InetSocketAddress server;
        private final SocketChannel channel;
        private final Selector selector;
        private final Reader[] readers;

        private int nextReaderIndex = 0;

        public Listener(Selector selector,InetSocketAddress address) throws IOException {
            this.selector = selector;
            this.server = address;
            readers = new Reader[10];
            for (int i = 0; i < readers.length; i++){
                try {
                    readers[i] = new Reader(Selector.open());
                    Thread thread = new Thread(readers[i]);
                    thread.setDaemon(true);
                    thread.start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (server.isUnresolved()){
                throw new IOException("Unknown host of server " + server);
            }
            channel = SocketChannel.open();
            channel.bind(server);
            channel.register(selector,SelectionKey.OP_ACCEPT);
        }

        private Reader getReader(){
            Reader reader = readers[nextReaderIndex];
            nextReaderIndex = (nextReaderIndex + 1)%readers.length;
            return reader;
        }


        public void end(){
            shouldRun = false;
            selector.wakeup();
        }

        @Override
        public void run() {
            while (shouldRun){
                try {
                    selector.select(60 * 1000);
                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = keys.iterator();
                    while (iterator.hasNext()){
                        SelectionKey key = iterator.next();
                        if (key.isValid() && key.isAcceptable()){
                            Connection con = new Connection((SocketChannel) key.channel());
                            key.attach(con);
                            connections.add(con);
                            getReader().registerChannel(key.channel());
                        }
                        iterator.remove();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private class Reader implements Runnable{

            private final Selector selector;

            public Reader(Selector selector){
                this.selector = selector;
            }

            public SelectionKey registerChannel(SelectableChannel channel) throws ClosedChannelException {
                return channel.register(selector,SelectionKey.OP_READ);
            }

            @Override
            public void run() {
                while (true){
                    try {
                        selector.select();
                        selector.selectedKeys().stream()
                                .filter(SelectionKey::isValid)
                                .forEach(this::doRead);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
            }

            private void doRead(SelectionKey key){
                Connection connection = (Connection)key.attachment();
                connection.readOneRPC();
            }
        }
    }

    private class Handler extends Thread{

        private final Responer responer;

        public Handler(Responer responser){
            this.responer = responser;
        }

        @Override
        public void run() {
            while (true){
                while (callQueue.isEmpty()){
                    try {
                        callQueue.wait();
                    } catch (InterruptedException e) {
                    }
                }
                Call call = callQueue.poll();
                if (call != null){
                    Object value = null;
                    Throwable exception = null;
                    try {
                         value = call(call);
                    } catch (Throwable throwable) {
                        exception = throwable;
                    }
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    DataOutputStream ds = new DataOutputStream(os);
                    try {
                        Gson gson = new Gson();
                        ds.writeInt(call.index);
                        if (exception != null){
                            ds.writeBoolean(true);
                            String exeStr = gson.toJson(exception);
                            ds.writeInt(exeStr.length());
                            ds.write(exeStr.getBytes());
                        }else {
                            ds.writeBoolean(false);
                            String valueStr = gson.toJson(value);
                            ds.writeInt(valueStr.length());
                            ds.write(valueStr.getBytes());
                        }
                        call.response = ByteBuffer.wrap(os.toByteArray());
                        responer.doResponse(call);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private class Responer extends Thread{
        private final Selector selector;

        public Responer() throws IOException {
            selector = Selector.open();
            this.setDaemon(true);
        }

        @Override
        public void run() {
            while (true){
                try {
                    selector.select();
                    Set<SelectionKey> keySet = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = keySet.iterator();
                    while (iterator.hasNext()){
                        SelectionKey key = iterator.next();
                        if (key.isValid() && key.isWritable()){
                            Connection connection = (Connection)key.attachment();
                            processOneCall(connection);
                        }
                        iterator.remove();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
        }

        public void doResponse(Call call) throws IOException {
            synchronized (call.connection){
                call.connection.calls.addLast(call);
                if (call.connection.calls.size() == 1){
                    processOneCall(call.connection);
                }
            }
        }

        private void processOneCall(Connection connection) throws IOException {
            Call call = connection.calls.removeFirst();
            if (call == null){
                return;
            }
            try {
                connection.channel.write(call.response);
                if (call.response.hasRemaining()){
                    connection.calls.addFirst(call);
                    if (connection.channel.keyFor(selector) == null){
                        SelectionKey key = connection.channel.register(selector,SelectionKey.OP_WRITE);
                        key.attach(connection);
                    }
                }
            } catch (IOException e) {
                throw e;
            }

        }
    }

    abstract Object call(Object param) throws Throwable;
}
