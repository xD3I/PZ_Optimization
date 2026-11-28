package pzopt;

import java.nio.*;
import java.nio.file.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Optional hidden-context GPU regression; enable with -Dpzopt.test.rainGpu=true. */
public final class RainMaskGpuTest {
    static final String BEFORE = "vec2 g = pzRainSlope(fc) * pzRainP.z;";
    static final String AFTER = "vec2 g = pzRainSlope(fc) * pzRainP.z * clamp(puddlesDirNone, 0.0, 1.0);";
    static int shader(int type, String code) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, code); GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0)
            throw new AssertionError(GL20.glGetShaderInfoLog(id));
        return id;
    }
    static int program(String code, String quality) {
        String vertex = "#version 120\nuniform float mask;\n"
            + "varying float puddlesDirNE, puddlesDirNW, puddlesDirAll, puddlesDirNone, vDepth, pzWakeNear;\n"
            + "varying vec4 vertColour;\nvoid main(){gl_Position=gl_Vertex; puddlesDirNE=0.0; puddlesDirNW=0.0;"
            + "puddlesDirAll=mask; puddlesDirNone=mask; vDepth=0.5; pzWakeNear=0.0; vertColour=vec4(1.0);}";
        int vs=shader(GL20.GL_VERTEX_SHADER,vertex), fs=shader(GL20.GL_FRAGMENT_SHADER,code+"\nvoid main(){"+quality+"();}\n");
        int p=GL20.glCreateProgram(); GL20.glAttachShader(p,vs); GL20.glAttachShader(p,fs); GL20.glLinkProgram(p);
        if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0) throw new AssertionError(GL20.glGetProgramInfoLog(p));
        GL20.glDeleteShader(vs); GL20.glDeleteShader(fs); return p;
    }
    static void v4(int p,String n,float a,float b,float c,float d){GL20.glUniform4f(GL20.glGetUniformLocation(p,n),a,b,c,d);}
    static FloatBuffer draw(int p, float mask, int mode) {
        GL20.glUseProgram(p);
        GL20.glUniform1f(GL20.glGetUniformLocation(p,"mask"),mask);
        v4(p,"WOffset",0,0,256,256); v4(p,"WViewport",0,0,64,64);
        v4(p,"pzRainP",mode,0.6f,1,0.5f); v4(p,"pzRainW",0,0,1f/64,0.01f);
        GL20.glUniform1i(GL20.glGetUniformLocation(p,"pzRainVol"),22);
        FloatBuffer params=BufferUtils.createFloatBuffer(16);
        params.put(new float[]{0,0,0,0, 0,1,0.7f,2, 0,0,0,0, 0,0,0,0}).flip();
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,"PuddlesParams"),false,params);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT); GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-1,-1); GL11.glVertex2f(1,-1); GL11.glVertex2f(1,1); GL11.glVertex2f(-1,1); GL11.glEnd();
        FloatBuffer pixels=BufferUtils.createFloatBuffer(64*64*4);
        GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_FLOAT,pixels);
        int error=GL11.glGetError(); if(error!=0)throw new AssertionError("GL error "+error);
        for(int i=0;i<pixels.limit();i++)if(!Float.isFinite(pixels.get(i)))throw new AssertionError("Nonfinite framebuffer component "+i);
        return pixels;
    }
    static float maxAlpha(FloatBuffer p){float max=0;for(int i=3;i<p.limit();i+=4)max=Math.max(max,p.get(i));return max;}
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("pzopt.test.rainGpu")) {
            System.out.println("RainMaskGpuTest skipped (enable -Dpzopt.test.rainGpu=true with a graphics driver)");
            return;
        }
        zombie.core.random.RandStandard.INSTANCE.init();
        if(!GLFW.glfwInit())throw new AssertionError("GLFW init");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(64,64,"Rain mask test",0,0);
        if(window==0)throw new AssertionError("GLFW hidden context");
        try{
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); GL11.glViewport(0,0,64,64);
            System.out.println("GPU "+GL11.glGetString(GL11.GL_RENDERER));
            int framebuffer=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);
            int tex=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,64,64,0,GL11.GL_RGBA,GL11.GL_FLOAT,(ByteBuffer)null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,tex,0);
            if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("FBO");
            // Deterministic nonzero flipbook slope; the shader and its final composition remain original.
            GL13.glActiveTexture(GL13.GL_TEXTURE0+22);int volume=GL11.glGenTextures();GL11.glBindTexture(GL12.GL_TEXTURE_3D,volume);
            FloatBuffer value=BufferUtils.createFloatBuffer(4).put(new float[]{0.7f,0.55f,0.6f,0.65f});value.flip();
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D,0,GL30.GL_RGBA32F,1,1,1,0,GL11.GL_RGBA,GL11.GL_FLOAT,value);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,0);
            String raw=Files.readString(Path.of("media/shaders/puddles_common.frag.glsl"));
            var patch=Class.forName("pzopt.Ripples").getDeclaredMethod("patchText",String.class,boolean.class);
            patch.setAccessible(true);
            String loaded=(String)patch.invoke(null,raw,false);
            if(loaded==null||!loaded.contains(AFTER)||loaded.contains(BEFORE))throw new AssertionError("Actual shader output does not respect the puddle mask");
            String old=loaded.replace(AFTER,BEFORE);
            if(old==null||!old.contains(BEFORE))throw new AssertionError("Expected original rain slope");
            String fixed=loaded;
            for(String quality:new String[]{"puddlesMain","puddlesMain_MQ","puddlesMain_LQ"}){
                int original=program(old,quality), repaired=program(fixed,quality);
                for(int mode:new int[]{0,1,2,4}){
                    FloatBuffer before=draw(original,0,mode),after=draw(repaired,0,mode);
                    float a=maxAlpha(before),b=maxAlpha(after);
                    System.out.println(quality+" mode="+mode+" covered alpha old="+a+" fixed="+b);
                    if(b!=0)throw new AssertionError("Covered pixels gained alpha");
                    if(mode==4&&!quality.equals("puddlesMain_LQ")&&a<=0)throw new AssertionError("Original bug not reproduced");
                    FloatBuffer outsideA=draw(original,1,mode),outsideB=draw(repaired,1,mode);
                    for(int i=0;i<outsideA.limit();i++)if(Float.floatToIntBits(outsideA.get(i))!=Float.floatToIntBits(outsideB.get(i)))
                        throw new AssertionError("Uncovered pixels changed at "+i);
                }
                GL20.glDeleteProgram(original);GL20.glDeleteProgram(repaired);
            }
            System.out.println("PASS covered-rain reproduction and exact uncovered preservation");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
