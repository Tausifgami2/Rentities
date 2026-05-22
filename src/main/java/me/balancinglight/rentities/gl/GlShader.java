package me.balancinglight.rentities.gl;

import me.balancinglight.rentities.Rentities;

import static org.lwjgl.opengl.GL20C.*;

public class GlShader {

    public final int id;

    private GlShader(int programId) {
        this.id = programId;
    }

    public void bind()   { glUseProgram(id); }
    public void unbind() { glUseProgram(0);  }
    public void delete() { glDeleteProgram(id); }

    public int getUniformLocation(String name) {
        return glGetUniformLocation(id, name);
    }


    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String vertSource, fragSource;

        public Builder vert(String src) { this.vertSource = src; return this; }
        public Builder frag(String src) { this.fragSource = src; return this; }

        public GlShader compile() {
            int program = glCreateProgram();

            int vert = compileStage(GL_VERTEX_SHADER,   vertSource, "vertex");
            int frag = compileStage(GL_FRAGMENT_SHADER, fragSource, "fragment");

            glAttachShader(program, vert);
            glAttachShader(program, frag);
            glLinkProgram(program);

            glDetachShader(program, vert); glDeleteShader(vert);
            glDetachShader(program, frag); glDeleteShader(frag);

            String log = glGetProgramInfoLog(program);
            if (!log.isBlank()) Rentities.LOGGER.info("Shader link log: {}", log);

            if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
                glDeleteProgram(program);
                throw new RuntimeException("Shader link failed: " + log);
            }

            return new GlShader(program);
        }

        private static int compileStage(int type, String src, String label) {
            int shader = glCreateShader(type);
            glShaderSource(shader, src);
            glCompileShader(shader);

            String log = glGetShaderInfoLog(shader);
            if (!log.isBlank()) Rentities.LOGGER.info("{} shader log: {}", label, log);

            if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
                glDeleteShader(shader);
                throw new RuntimeException(label + " shader compile failed: " + log);
            }
            return shader;
        }
    }


    public static String loadResource(String path) {
        try (var stream = GlShader.class.getClassLoader()
                .getResourceAsStream("assets/rentities/shaders/" + path)) {
            if (stream == null) throw new RuntimeException("Shader not found: " + path);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }
}

