package com.cqyuanye.ipc;

/**
 * Created by Administrator on 2014/8/5.
 */
public class Test {

    public static void main(String[] args) {
        Object arrayObj = new String[]{"123","456","789"};
        Class clazz = arrayObj.getClass();
        System.out.println(clazz.getComponentType());
    }
}
