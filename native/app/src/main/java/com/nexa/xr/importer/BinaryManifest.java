package com.nexa.xr.importer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Edits compiled Android XML while retaining all original string indexes and resources. */
public final class BinaryManifest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private final List<String> strings = new ArrayList<>();
    private final List<Integer> resources = new ArrayList<>();
    private final List<Object> document = new ArrayList<>();
    private byte[] styles = new byte[0];
    private int[] styleOffsets = new int[0];
    private Element manifest;
    private static final class Element {
        byte[] start, end;
        final List<Object> children = new ArrayList<>();
        Element(byte[] start) { this.start = start; }
    }
    public BinaryManifest(byte[] data) throws IOException {
        if (data.length < 8 || u16(data, 0) != 3 || i32(data, 4) != data.length)
            throw new IOException("Manifest Android binário inválido");
        Deque<Element> stack = new ArrayDeque<>();
        boolean pool = false;
        for (int pos = u16(data, 2); pos < data.length;) {
            if (pos + 8 > data.length) throw new IOException("Manifest truncado");
            int size = i32(data, pos + 4), type = u16(data, pos);
            if (size < 8 || size > data.length - pos) throw new IOException("Chunk XML inválido");
            byte[] chunk = Arrays.copyOfRange(data, pos, pos + size);
            if (type == 1) {
                if (pool) throw new IOException("String pool duplicado");
                readPool(chunk); pool = true;
            } else if (type == 0x180) {
                if (u16(chunk, 2) != 8 || (size - 8) % 4 != 0) throw new IOException("Resource map inválido");
                for (int p = 8; p < size; p += 4) resources.add(i32(chunk, p));
            } else if (type == 0x102) {
                if (!pool || size < 36 || u16(chunk, 26) != 20 || 16L + u16(chunk, 24) + 20L*u16(chunk, 28) > size)
                    throw new IOException("Elemento XML inválido");
                Element e = new Element(chunk);
                if (stack.isEmpty()) document.add(e); else stack.peek().children.add(e);
                stack.push(e);
                if (manifest == null && "manifest".equals(name(e))) manifest = e;
            } else if (type == 0x103) {
                if (stack.isEmpty() || size < 24 || i32(stack.peek().start,20) != i32(chunk,20))
                    throw new IOException("XML sem fechamento válido");
                stack.pop().end = chunk;
            } else {
                if (stack.isEmpty()) document.add(chunk); else stack.peek().children.add(chunk);
            }
            pos += size;
        }
        if (!pool || manifest == null || !stack.isEmpty()) throw new IOException("Manifest incompleto");
    }
    public String packageName() { return value(manifest, null, "package"); }
    public void ensureStandalone() throws IOException {
        if (value(manifest, null, "split") != null || value(manifest, ANDROID, "sharedUserId") != null
                || "true".equals(value(manifest, ANDROID, "isFeatureSplit")))
            throw new IOException("APK dividido ou sharedUserId: exige preparação específica");
        for (Object o : manifest.children) if (o instanceof Element && "uses-split".equals(name((Element)o)))
            throw new IOException("Faltam splits: selecione um APK completo");
    }
    public byte[] adapt() throws IOException {
        ensureStandalone();
        Element application = child(manifest, "application");
        if (application == null) throw new IOException("APK sem application");
        set(application, ANDROID, "extractNativeLibs", 0x010104ea, 0x12, 1, null);
        for (Object obj : manifest.children) {
            if (!(obj instanceof Element)) continue;
            Element e = (Element)obj;
            if ("uses-feature".equals(name(e))) {
                String n = value(e, ANDROID, "name");
                if (n != null && n.startsWith("android.hardware.vr."))
                    set(e, ANDROID, "required", 0x0101028e, 0x12, 0, null);
            }
        }
        for (String p : new String[]{"org.khronos.openxr.permission.OPENXR", "org.khronos.openxr.permission.OPENXR_SYSTEM"}) {
            if (!hasNamed(manifest, "uses-permission", p)) {
                Element e = element("uses-permission"); named(e, p); beforeApplication(e);
            }
        }
        Element queries = child(manifest, "queries");
        if (queries == null) { queries = element("queries"); beforeApplication(queries); }
        for (String p : new String[]{"org.freedesktop.monado.openxr_runtime.out_of_process", "com.oculus.systemdriver",
                "org.khronos.openxr.runtime_broker", "org.khronos.openxr.system_runtime_broker"}) {
            if (!hasNamed(queries, "package", p)) { Element e = element("package"); named(e,p); queries.children.add(e); }
        }
        for (String p : new String[]{"org.khronos.openxr.runtime_broker", "org.khronos.openxr.system_runtime_broker"}) {
            boolean found = false;
            for (Object o : queries.children) if (o instanceof Element && "provider".equals(name((Element)o))
                    && p.equals(value((Element)o,ANDROID,"authorities"))) found = true;
            if (!found) { Element e = element("provider"); set(e,ANDROID,"authorities",0x01010018,3,0,p); queries.children.add(e); }
        }
        Element intent = element("intent"), action = element("action");
        named(action,"org.khronos.openxr.OpenXRRuntimeService"); intent.children.add(action);
        boolean service = false;
        for (Object o : queries.children) if (o instanceof Element && "intent".equals(name((Element)o))
                && hasNamed((Element)o,"action","org.khronos.openxr.OpenXRRuntimeService")) service = true;
        if (!service) queries.children.add(intent);
        for (Object o : application.children) {
            if (!(o instanceof Element)) continue;
            Element activity = (Element)o;
            if (!"activity".equals(name(activity)) && !"activity-alias".equals(name(activity))) continue;
            for (Object f : activity.children) if (f instanceof Element && "intent-filter".equals(name((Element)f))) {
                Element filter = (Element)f;
                if (hasNamed(filter,"action","android.intent.action.MAIN") && hasNamed(filter,"category","com.oculus.intent.category.VR")
                        && !hasNamed(filter,"category","android.intent.category.LAUNCHER")) {
                    Element category = element("category"); named(category,"android.intent.category.LAUNCHER"); filter.children.add(category);
                }
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(writePool());
        if (!resources.isEmpty()) {
            byte[] map = chunk(0x180,8,8+resources.size()*4);
            for (int n=0;n<resources.size();n++) put32(map,8+n*4,resources.get(n));
            body.write(map);
        }
        for (Object o : document) writeNode(body,o);
        byte[] xml = chunk(3,8,8+body.size());
        System.arraycopy(body.toByteArray(),0,xml,8,body.size());
        return xml;
    }
    private void beforeApplication(Element e) {
        int i=0; while(i<manifest.children.size() && !(manifest.children.get(i) instanceof Element && "application".equals(name((Element)manifest.children.get(i))))) i++;
        manifest.children.add(i,e);
    }
    private Element child(Element e,String n) { for(Object o:e.children) if(o instanceof Element && n.equals(name((Element)o))) return (Element)o; return null; }
    private boolean hasNamed(Element e,String tag,String val) { for(Object o:e.children) if(o instanceof Element && tag.equals(name((Element)o)) && val.equals(value((Element)o,ANDROID,"name"))) return true; return false; }
    private String name(Element e) { return string(i32(e.start,20)); }
    private String string(int index) { return index>=0 && index<strings.size()?strings.get(index):null; }
    private String value(Element e,String ns,String n) {
        int begin=16+u16(e.start,24);
        for(int i=0;i<u16(e.start,28);i++) {
            int p=begin+i*20;
            if(Objects.equals(ns,string(i32(e.start,p))) && n.equals(string(i32(e.start,p+4)))) {
                int raw=i32(e.start,p+8),type=e.start[p+15]&255, data=i32(e.start,p+16);
                if(raw>=0) return string(raw);
                if(type==3) return string(data);
                if(type==0x12) return data!=0?"true":"false";
                return Integer.toString(data);
            }
        }
        return null;
    }
    private int intern(String s) { int i=strings.indexOf(s); if(i<0) {i=strings.size();strings.add(s);}return i; }
    private int attributeName(String n,int id) {
        for(int i=0;i<strings.size();i++) if(n.equals(strings.get(i)) && (i<resources.size()?resources.get(i):0)==id) return i;
        int i=strings.size();strings.add(n);while(resources.size()<=i)resources.add(0);resources.set(i,id);return i;
    }
    private void named(Element e,String val) { set(e,ANDROID,"name",0x01010003,3,0,val); }
    private void set(Element e,String ns,String n,int id,int type,int data,String text) {
        List<byte[]> attrs=new ArrayList<>();
        int begin=16+u16(e.start,24), count=u16(e.start,28);
        byte[] specialId=null,specialClass=null,specialStyle=null;
        for(int i=0;i<count;i++) {
            byte[] a=Arrays.copyOfRange(e.start,begin+i*20,begin+(i+1)*20);
            if(Objects.equals(ns,string(i32(a,0))) && n.equals(string(i32(a,4)))) continue;
            attrs.add(a);
            if(i+1==u16(e.start,30))specialId=a;
            if(i+1==u16(e.start,32))specialClass=a;
            if(i+1==u16(e.start,34))specialStyle=a;
        }
        byte[] a=new byte[20];put32(a,0,ns==null?-1:intern(ns));put32(a,4,attributeName(n,id));
        int t=text==null?-1:intern(text);put32(a,8,t);put16(a,12,8);a[15]=(byte)type;put32(a,16,type==3?t:data);attrs.add(a);
        attrs.sort((x,y)->Integer.compareUnsigned(resource(i32(x,4)),resource(i32(y,4))));
        byte[] fresh=Arrays.copyOf(e.start,begin+20*attrs.size());put32(fresh,4,fresh.length);put16(fresh,28,attrs.size());
        put16(fresh,30,specialId==null?0:attrs.indexOf(specialId)+1);put16(fresh,32,specialClass==null?0:attrs.indexOf(specialClass)+1);put16(fresh,34,specialStyle==null?0:attrs.indexOf(specialStyle)+1);
        for(int i=0;i<attrs.size();i++)System.arraycopy(attrs.get(i),0,fresh,begin+i*20,20);e.start=fresh;
    }
    private int resource(int i) {return i>=0&&i<resources.size()?resources.get(i):0;}
    private Element element(String tag) {
        byte[] b=chunk(0x102,16,36);put32(b,8,1);put32(b,12,-1);put32(b,16,-1);put32(b,20,intern(tag));put16(b,24,20);put16(b,26,20);
        Element e=new Element(b);e.end=chunk(0x103,16,24);put32(e.end,8,1);put32(e.end,12,-1);put32(e.end,16,-1);put32(e.end,20,intern(tag));return e;
    }
    private void writeNode(OutputStream out,Object o)throws IOException {if(o instanceof byte[])out.write((byte[])o);else {Element e=(Element)o;out.write(e.start);for(Object c:e.children)writeNode(out,c);out.write(e.end);}}
    private void readPool(byte[] b)throws IOException {
        if(b.length<28)throw new IOException("String pool curto");
        int count=i32(b,8),stylesCount=i32(b,12),flags=i32(b,16),start=i32(b,20),styleStart=i32(b,24),header=u16(b,2);
        if(count<0||count>100000||stylesCount<0||stylesCount>count||header<28||header+4L*(count+stylesCount)>b.length||start<0||start>b.length)throw new IOException("String pool inválido");
        styleOffsets=new int[stylesCount];for(int i=0;i<stylesCount;i++)styleOffsets[i]=i32(b,header+count*4+i*4);
        if(stylesCount>0) {if(styleStart<start||styleStart>b.length)throw new IOException("Styles inválidos");styles=Arrays.copyOfRange(b,styleStart,b.length);}
        boolean utf8=(flags&256)!=0;
        try {
            for(int i=0;i<count;i++) {
                int p=Math.addExact(start,i32(b,header+4*i));
                if(utf8) {int[] first=length8(b,p);int[] len=length8(b,first[1]);p=len[1];if(p<start||len[0]>b.length-p-1)throw new IOException("String inválida");strings.add(new String(b,p,len[0],StandardCharsets.UTF_8));}
                else {int[] len=length16(b,p);p=len[1];if(p<start||2L*len[0]>b.length-p-2)throw new IOException("String inválida");strings.add(new String(b,p,len[0]*2,StandardCharsets.UTF_16LE));}
            }
        }catch(IndexOutOfBoundsException|ArithmeticException ex){throw new IOException("String pool truncado",ex);}
    }
    private byte[] writePool()throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();List<Integer> offsets=new ArrayList<>();
        for(String s:strings){offsets.add(bytes.size());byte[] u=s.getBytes(StandardCharsets.UTF_8);if(s.length()>32767||u.length>32767)throw new IOException("String muito longa");writeLength8(bytes,s.length());writeLength8(bytes,u.length);bytes.write(u);bytes.write(0);}
        while(bytes.size()%4!=0)bytes.write(0);
        int start=28+4*(strings.size()+styleOffsets.length),styleStart=styles.length==0?0:start+bytes.size();
        byte[] b=chunk(1,28,start+bytes.size()+styles.length);put32(b,8,strings.size());put32(b,12,styleOffsets.length);put32(b,16,256);put32(b,20,start);put32(b,24,styleStart);
        for(int i=0;i<offsets.size();i++)put32(b,28+i*4,offsets.get(i));for(int i=0;i<styleOffsets.length;i++)put32(b,28+strings.size()*4+i*4,styleOffsets[i]);
        System.arraycopy(bytes.toByteArray(),0,b,start,bytes.size());if(styles.length>0)System.arraycopy(styles,0,b,styleStart,styles.length);return b;
    }
    private static int[] length8(byte[]b,int p){int n=b[p++]&255;if((n&128)!=0)n=((n&127)<<8)|(b[p++]&255);return new int[]{n,p};}
    private static int[] length16(byte[]b,int p){int n=u16(b,p);p+=2;if((n&32768)!=0){n=((n&32767)<<16)|u16(b,p);p+=2;}return new int[]{n,p};}
    private static void writeLength8(OutputStream o,int n)throws IOException{if(n>127)o.write((n>>8)|128);o.write(n&255);}
    private static byte[] chunk(int type,int header,int size){byte[]b=new byte[size];put16(b,0,type);put16(b,2,header);put32(b,4,size);return b;}
    private static int u16(byte[]b,int p){return(b[p]&255)|((b[p+1]&255)<<8);}
    private static int i32(byte[]b,int p){return u16(b,p)|(u16(b,p+2)<<16);}
    private static void put16(byte[]b,int p,int n){b[p]=(byte)n;b[p+1]=(byte)(n>>8);}
    private static void put32(byte[]b,int p,int n){put16(b,p,n);put16(b,p+2,n>>16);}
}
