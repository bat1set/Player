package main

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_LUMINANCE
import org.lwjgl.opengl.GL11.GL_LUMINANCE_ALPHA
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.GL_QUADS
import org.lwjgl.opengl.GL11.GL_RGB
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP
import org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT
import org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH
import org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS
import org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBegin
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glColor3f
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glEnd
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glGetError
import org.lwjgl.opengl.GL11.glPixelStorei
import org.lwjgl.opengl.GL11.glTexCoord2f
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glTexSubImage2D
import org.lwjgl.opengl.GL11.glVertex2f
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.GL_TEXTURE1
import org.lwjgl.opengl.GL13.GL_TEXTURE2
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL20.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL20.GL_LINK_STATUS
import org.lwjgl.opengl.GL20.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL20.glAttachShader
import org.lwjgl.opengl.GL20.glBindAttribLocation
import org.lwjgl.opengl.GL20.glCompileShader
import org.lwjgl.opengl.GL20.glCreateProgram
import org.lwjgl.opengl.GL20.glCreateShader
import org.lwjgl.opengl.GL20.glDeleteProgram
import org.lwjgl.opengl.GL20.glDeleteShader
import org.lwjgl.opengl.GL20.glDisableVertexAttribArray
import org.lwjgl.opengl.GL20.glEnableVertexAttribArray
import org.lwjgl.opengl.GL20.glGetProgramInfoLog
import org.lwjgl.opengl.GL20.glGetProgrami
import org.lwjgl.opengl.GL20.glGetShaderInfoLog
import org.lwjgl.opengl.GL20.glGetShaderi
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glLinkProgram
import org.lwjgl.opengl.GL20.glShaderSource
import org.lwjgl.opengl.GL20.glUniform1i
import org.lwjgl.opengl.GL20.glUseProgram
import org.lwjgl.opengl.GL20.glVertexAttribPointer
import java.nio.ByteBuffer

class VideoFrameRenderer(private val debugVideo: Boolean = false) : AutoCloseable {
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
    private val yuvPositions = BufferUtils.createFloatBuffer(8).apply {
        put(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
        )
        flip()
    }
    private val yuvTexCoords = BufferUtils.createFloatBuffer(8).apply {
        put(
            floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
            )
        )
        flip()
    }

    fun initialize() {
        rgbTexture = createTexture()
        yTexture = createTexture()
        uTexture = createTexture()
        vTexture = createTexture()
        uvTexture = createTexture()
        yuv420pProgram = createProgram(YUV420P_FRAGMENT_SHADER)
        nv12Program = createProgram(NV12_FRAGMENT_SHADER)
        configureSamplers(yuv420pProgram, "YUV420P", "texY", "texU", "texV")
        configureSamplers(nv12Program, "NV12", "texY", "texUV")
    }

    fun upload(frame: VideoFrame) {
        try {
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0)
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0)
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0)
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

        uploadPlane(yTexture, frame.y, frame.width, frame.height, GL_LUMINANCE, GL_LUMINANCE, allocate)
        uploadPlane(uTexture, frame.u, chromaWidth, chromaHeight, GL_LUMINANCE, GL_LUMINANCE, allocate)
        uploadPlane(vTexture, frame.v, chromaWidth, chromaHeight, GL_LUMINANCE, GL_LUMINANCE, allocate)
        finishUpload(FrameKind.YUV420P, frame.width, frame.height)
    }

    private fun uploadNv12(frame: Nv12VideoFrame) {
        val chromaWidth = (frame.width + 1) / 2
        val chromaHeight = (frame.height + 1) / 2
        val allocate = uploadedKind != FrameKind.NV12 ||
            textureWidth != frame.width ||
            textureHeight != frame.height

        uploadPlane(yTexture, frame.y, frame.width, frame.height, GL_LUMINANCE, GL_LUMINANCE, allocate)
        uploadPlane(uvTexture, frame.uv, chromaWidth, chromaHeight, GL_LUMINANCE_ALPHA, GL_LUMINANCE_ALPHA, allocate)
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
            logGlError("glTexImage2D")
        }
        val data = uploadSlice(buffer, width * height * componentsFor(format))
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, GL_UNSIGNED_BYTE, data)
        logGlError("glTexSubImage2D")
    }

    private fun uploadSlice(buffer: ByteBuffer, expectedBytes: Int): ByteBuffer {
        val data = buffer.duplicate()
        data.position(0)
        if (data.capacity() < expectedBytes) {
            if (debugVideo) {
                Log.info(
                    "Native YUV warning: upload buffer capacity=${data.capacity()} " +
                        "is smaller than expected=$expectedBytes"
                )
            }
            data.limit(data.capacity())
            return data
        }
        if (debugVideo && data.limit() < expectedBytes) {
            Log.info(
                "Native YUV warning: upload buffer limit=${data.limit()} " +
                    "is smaller than expected=$expectedBytes; using capacity-bounded slice"
            )
        }
        data.limit(expectedBytes)
        return data
    }

    private fun componentsFor(format: Int): Int {
        return when (format) {
            GL_LUMINANCE_ALPHA -> 2
            else -> 1
        }
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
        glColor3f(1f, 1f, 1f)
        glEnable(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, rgbTexture)
        drawQuad()
        glBindTexture(GL_TEXTURE_2D, 0)
        glDisable(GL_TEXTURE_2D)
    }

    private fun renderYuv420p() {
        glUseProgram(yuv420pProgram)
        glColor3f(1f, 1f, 1f)
        bindTextureUniform(yuv420pProgram, "texY", GL_TEXTURE0, yTexture, 0)
        bindTextureUniform(yuv420pProgram, "texU", GL_TEXTURE1, uTexture, 1)
        bindTextureUniform(yuv420pProgram, "texV", GL_TEXTURE2, vTexture, 2)
        drawYuvQuad()
        glUseProgram(0)
        unbindYuvTextureUnits()
    }

    private fun renderNv12() {
        glUseProgram(nv12Program)
        glColor3f(1f, 1f, 1f)
        bindTextureUniform(nv12Program, "texY", GL_TEXTURE0, yTexture, 0)
        bindTextureUniform(nv12Program, "texUV", GL_TEXTURE1, uvTexture, 1)
        drawYuvQuad()
        glUseProgram(0)
        unbindYuvTextureUnits()
    }

    private fun bindTextureUniform(program: Int, name: String, unit: Int, texture: Int, index: Int) {
        glActiveTexture(unit)
        glEnable(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, texture)
        val location = glGetUniformLocation(program, name)
        if (location >= 0) {
            glUniform1i(location, index)
        } else if (debugVideo) {
            Log.info("Native YUV warning: uniform '$name' is not active in program $program")
        }
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

    private fun drawYuvQuad() {
        yuvPositions.position(0)
        yuvTexCoords.position(0)
        glEnableVertexAttribArray(POSITION_ATTRIBUTE)
        glEnableVertexAttribArray(TEX_COORD_ATTRIBUTE)
        try {
            glVertexAttribPointer(POSITION_ATTRIBUTE, 2, GL_FLOAT, false, 0, yuvPositions)
            glVertexAttribPointer(TEX_COORD_ATTRIBUTE, 2, GL_FLOAT, false, 0, yuvTexCoords)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        } finally {
            glDisableVertexAttribArray(TEX_COORD_ATTRIBUTE)
            glDisableVertexAttribArray(POSITION_ATTRIBUTE)
        }
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

    private fun configureSamplers(program: Int, label: String, vararg samplerNames: String) {
        glUseProgram(program)
        samplerNames.forEachIndexed { index, name ->
            val location = glGetUniformLocation(program, name)
            if (location >= 0) {
                glUniform1i(location, index)
            }
            if (debugVideo) {
                Log.info("Native YUV shader $label sampler: $name location=$location unit=$index")
            }
        }
        glUseProgram(0)
        logGlError("configureSamplers($label)")
    }

    private fun createProgram(fragmentShaderSource: String): Int {
        val vertex = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource)
        val program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glBindAttribLocation(program, POSITION_ATTRIBUTE, "position")
        glBindAttribLocation(program, TEX_COORD_ATTRIBUTE, "texCoord")
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

    private fun logGlError(operation: String) {
        if (!debugVideo) return
        val error = glGetError()
        if (error != GL_NO_ERROR) {
            Log.info("OpenGL warning after $operation: 0x${error.toString(16)}")
        }
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
        private const val POSITION_ATTRIBUTE = 0
        private const val TEX_COORD_ATTRIBUTE = 1

        private const val VERTEX_SHADER = """
            #version 120
            attribute vec2 position;
            attribute vec2 texCoord;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = texCoord;
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        private const val YUV420P_FRAGMENT_SHADER = """
            #version 120
            varying vec2 vTexCoord;
            uniform sampler2D texY;
            uniform sampler2D texU;
            uniform sampler2D texV;
            void main() {
                vec2 tc = vTexCoord;
                float y = texture2D(texY, tc).r - 0.0625;
                float u = texture2D(texU, tc).r - 0.5;
                float v = texture2D(texV, tc).r - 0.5;
                vec3 rgb;
                rgb.r = 1.1643 * y + 1.5958 * v;
                rgb.g = 1.1643 * y - 0.39173 * u - 0.81290 * v;
                rgb.b = 1.1643 * y + 2.0170 * u;
                gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
            }
        """

        private const val NV12_FRAGMENT_SHADER = """
            #version 120
            varying vec2 vTexCoord;
            uniform sampler2D texY;
            uniform sampler2D texUV;
            void main() {
                vec2 tc = vTexCoord;
                float y = texture2D(texY, tc).r - 0.0625;
                vec2 uv = texture2D(texUV, tc).ra - vec2(0.5, 0.5);
                vec3 rgb;
                rgb.r = 1.1643 * y + 1.5958 * uv.y;
                rgb.g = 1.1643 * y - 0.39173 * uv.x - 0.81290 * uv.y;
                rgb.b = 1.1643 * y + 2.0170 * uv.x;
                gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
            }
        """
    }
}
