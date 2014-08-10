package test.ipc;

import java.io.*;

/**
 * Created by Kali on 14-8-10.
 */
public class SerTest {


    public static class Apple implements Serializable{
        String color;
        long price;

        public Apple(String color,long price){
            this.color = color;
            this.price = price;
        }

        @Override
        public String toString() {
            return color + " apple with price of " + price;
        }
    }

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
        Object obj = new Apple("Red",2);
        objectOutputStream.writeObject(obj);

        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
        Object o = objectInputStream.readObject();
        System.out.println(o);
    }
}
