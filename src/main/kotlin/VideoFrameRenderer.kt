package main

import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_QUADS
import org.lwjgl.opengl.GL11.GL_RED
import org.lwjgl.opengl.GL11.GL_RGB
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBegin
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glEnd
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glPixelStorei
import org.lwjgl.opengl.GL11.glTexCoord2f
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glTexSubImage2D
import org.lwjgl.opengl.GL11.glVertex2f
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.GL_TEXTURE1
import org.lwjgl.opengl.GL13.GL_TEXTURE2
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.GL_R8
import org.lwjgl.opengl.GL30.GL_RG
import org.lwjgl.opengl.GL30.GL_RG8
import org.lwjgl.opengl.GL20.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL20.GL_LINK_STATUS
import org.lwjgl.opengl.GL20.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL20.glAttachShader
import org.lwjgl.opengl.GL20.glCompileShader
import org.lwjgl.opengl.GL20.glCreateProgram
import org.lwjgl.opengl.GL20.glCreateShader
import org.lwjgl.opengl.GL20.glDeleteProgram
import org.lwjgl.opengl.GL20.glDeleteShader
import org.lwjgl.opengl.GL20.glGetProgramInfoLog
import org.lwjgl.opengl.GL20.glGetProgrami
import org.lwjgl.opengl.GL20.glGetShaderInfoLog
import org.lwjgl.opengl.GL20.glGetShaderi
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glLinkProgram
import org.lwjgl.opengl.GL20.glShaderSource
import org.lwjgl.opengl.GL20.glUniform1i
import org.lwjgl.opengl.GL20.glUseProgram
import java.nio.ByteBuffer

class VideoFrameRenderer : AutoCloseable {
    private var rgbTexture = 0
    private var yTexture = 0
    private var uTexture = 0
    private var vTexture = 0
    private var uvTexture = 0
    private var yuv420pProgram = 0
    private var nv12Program = 0
    private var uploadedKind = FrameKind.NONE
    private var textureWidth = 0
    private var textureHeight = 0

    fun initialize() {
        rgbTexture = createTexture()
        yTexture = createTexture()
        uTexture = createTexture()
        vTexture = createTexture()
        uvTexture = createTexture()
        yuv420pProgram = createProgram(YUV420P_FRAGMENT_SHADER)
        nv12Program = createProgram(NV12_FRAGMENT_SHADER)
    }

    fun upload(frame: VideoFrame) {
        try {
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            when (frame) {
                is RgbVideoFrame -> uploadRgb(frame)
                is Yuv420pVideoFrame -> uploadYuv420p(frame)
                is Nv12VideoFrame -> uploadNv12(frame)
            }
        } finally {
            frame.close()
        }
    }

    fun render() {
        glClear(GL_COLOR_BUFFER_BIT)
        when (uploadedKind) {
            FrameKind.RGB -> renderRgb()
            FrameKind.YUV420P -> renderYuv420p()
            FrameKind.NV12 -> renderNv12()
            FrameKind.NONE -> Unit
        }
    }

    private fun uploadRgb(frame: RgbVideoFrame) {
        glBindTexture(GL_TEXTURE_2D, rgbTexture)
        if (uploadedKind != FrameKind.RGB || textureWidth != frame.width || textureHeight != frame.height) {
            glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGB, frame.width, frame.height,
                0, GL_RGB, GL_UNSIGNED_BYTE, null as ByteBuffer?
            )
        }
        frame.rgb.position(0)
        glTexSubImage2D(
            GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
            GL_RGB, GL_UNSIGNED_BYTE, frame.rgb
        )
        finishUpload(FrameKind.RGB, frame.width, frame.height)
    }

    private fun uploadYuv420p(frame: Yuv420pVideoFrame) {
        val chromaWidth = (frame.width + 1) / 2
        val chromaHeight = (frame.height + 1) / 2
        val allocate = uploadedKind != FrameKind.YUV420P ||
            textureWidth != frame.width ||
            textureHeight != frame.height

        uploadPlane(yTexture, frame.y, frame.width, frame.height, GL_R8, GL_RED, allocate)
        uploadPlane(uTexture, frame.u, chromaWidth, chromaHeight, GL_R8, GL_RED, allocate)
        uploadPlane(vTexture, frame.v, chromaWidth, chromaHeight, GL_R8, GL_RED, allocate)
        finishUpload(FrameKind.YUV420P, frame.width, frame.height)
    }

    private fun uploadNv12(frame: Nv12VideoFrame) {
        val chromaWidth = (frame.width + 1) / 2
        val chromaHeight = (frame.height + 1) / 2
        val allocate = uploadedKind != FrameKind.NV12 ||
            textureWidth != frame.width ||
            textureHeight != frame.height

        uploadPlane(yTexture, frame.y, frame.width, frame.height, GL_R8, GL_RED, allocate)
        uploadPlane(uvTexture, frame.uv, chromaWidth, chromaHeight, GL_RG8, GL_RG, allocate)
        finishUpload(FrameKind.NV12, frame.width, frame.height)
    }

    private fun uploadPlane(
        texture: Int,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        internalFormat: Int,
        format: Int,
        allocate: Boolean
    ) {
        glBindTexture(GL_TEXTURE_2D, texture)
        if (allocate) {
            glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, GL_UNSIGNED_BYTE, null as ByteBuffer?)
        }
        buffer.position(0)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, GL_UNSIGNED_BYTE, buffer)
    }

    private fun finishUpload(kind: FrameKind, width: Int, height: Int) {
        uploadedKind = kind
        textureWidth = width
        textureHeight = height
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    private fun renderRgb() {
        glUseProgram(0)
        glActiveTexture(GL_TEXTURE0)
        glEnable(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, rgbTexture)
        drawQuad()
        glBindTexture(GL_TEXTURE_2D, 0)
        glDisable(GL_TEXTURE_2D)
    }

    private fun renderYuv420p() {
        glUseProgram(yuv420pProgram)
        bindTextureUniform(yuv420pProgram, "texY", GL_TEXTURE0, yTexture, 0)
        bindTextureUniform(yuv420pProgram, "texU", GL_TEXTURE1, uTexture, 1)
        bindTextureUniform(yuv420pProgram, "texV", GL_TEXTURE2, vTexture, 2)
        drawQuad()
        glUseProgram(0)
        unbindYuvTextureUnits()
    }

    private fun renderNv12() {
        glUseProgram(nv12Program)
        bindTextureUniform(nv12Program, "texY", GL_TEXTURE0, yTexture, 0)
        bindTextureUniform(nv12Program, "texUV", GL_TEXTURE1, uvTexture, 1)
        drawQuad()
        glUseProgram(0)
        unbindYuvTextureUnits()
    }

    private fun bindTextureUniform(program: Int, name: String, unit: Int, texture: Int, index: Int) {
        glActiveTexture(unit)
        glEnable(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, texture)
        glUniform1i(glGetUniformLocation(program, name), index)
    }

    private fun unbindYuvTextureUnits() {
        listOf(GL_TEXTURE2, GL_TEXTURE1, GL_TEXTURE0).forEach { unit ->
            glActiveTexture(unit)
            glBindTexture(GL_TEXTURE_2D, 0)
            glDisable(GL_TEXTURE_2D)
        }
        glActiveTexture(GL_TEXTURE0)
    }

    private fun drawQuad() {
        glBegin(GL_QUADS)
        glTexCoord2f(0f, 1f); glVertex2f(-1f, -1f)
        glTexCoord2f(1f, 1f); glVertex2f(1f, -1f)
        glTexCoord2f(1f, 0f); glVertex2f(1f, 1f)
        glTexCoord2f(0f, 0f); glVertex2f(-1f, 1f)
        glEnd()
    }

    private fun createTexture(): Int {
        val texture = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, 0)
        return texture
    }

    private fun createProgram(fragmentShaderSource: String): Int {
        val vertex = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource)
        val program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
            val log = glGetProgramInfoLog(program)
            glDeleteShader(vertex)
            glDeleteShader(fragment)
            glDeleteProgram(program)
            throw RuntimeException("Failed to link YUV shader: $log")
        }
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            val log = glGetShaderInfoLog(shader)
            glDeleteShader(shader)
            throw RuntimeException("Failed to compile shader: $log")
        }
        return shader
    }

    override fun close() {
        listOf(rgbTexture, yTexture, uTexture, vTexture, uvTexture)
            .filter { it != 0 }
            .forEach { glDeleteTextures(it) }
        if (yuv420pProgram != 0) glDeleteProgram(yuv420pProgram)
        if (nv12Program != 0) glDeleteProgram(nv12Program)
    }

    private enum class FrameKind {
        NONE,
        RGB,
        YUV420P,
        NV12
    }

    companion object {
        private const val VERTEX_SHADER = """
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = gl_MultiTexCoord0.st;
                gl_Position = ftransform();
            }
        """

        private const val YUV420P_FRAGMENT_SHADER = """
            varying vec2 vTexCoord;
            uniform sampler2D texY;
            uniform sampler2D texU;
            uniform sampler2D texV;
            void main() {
                float y = texture2D(texY, vTexCoord).r;
                float u = texture2D(texU, vTexCoord).r - 0.5;
                float v = texture2D(texV, vTexCoord).r - 0.5;
                vec3 rgb;
                rgb.r = y + 1.402 * v;
                rgb.g = y - 0.344136 * u - 0.714136 * v;
                rgb.b = y + 1.772 * u;
                gl_FragColor = vec4(rgb, 1.0);
            }
        """

        private const val NV12_FRAGMENT_SHADER = """
            varying vec2 vTexCoord;
            uniform sampler2D texY;
            uniform sampler2D texUV;
            void main() {
                float y = texture2D(texY, vTexCoord).r;
                vec2 uv = texture2D(texUV, vTexCoord).rg - vec2(0.5, 0.5);
                vec3 rgb;
                rgb.r = y + 1.402 * uv.y;
                rgb.g = y - 0.344136 * uv.x - 0.714136 * uv.y;
                rgb.b = y + 1.772 * uv.x;
                gl_FragColor = vec4(rgb, 1.0);
            }
        """
    }
}
