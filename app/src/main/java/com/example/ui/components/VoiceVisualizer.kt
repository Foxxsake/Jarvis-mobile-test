package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.engine.voice.VoiceSessionState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val CyanBright = Color(0xFF06D6F5)
private val CyanGlow = Color(0xFF00E5FF)
private val CyanDark = Color(0xFF004D61)
private val BlueDeep = Color(0xFF000033)
private val WhiteHot = Color(0xFFFFFFFF)
private val PurpleMist = Color(0xFF7B1FA2)
private val TealMist = Color(0xFF00BFA5)

@Composable
fun VoiceVisualizer(
    state: VoiceSessionState,
    modifier: Modifier = Modifier,
    size: Dp = 260.dp,
    onClick: () -> Unit = {}
) {
    val isListening = state == VoiceSessionState.LISTENING || state == VoiceSessionState.TRANSCRIBING
    val isSpeaking = state == VoiceSessionState.SPEAKING
    val isProcessing = state == VoiceSessionState.PROCESSING
    val isActive = isListening || isSpeaking || isProcessing
    val isError = state == VoiceSessionState.ERROR

    val infiniteTransition = rememberInfiniteTransition(label = "viz")

    val ring1Rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(20000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "ring1"
    )
    val ring2Rotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(30000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "ring2"
    )
    val ring3Rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(40000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "ring3"
    )

    val pulseSpeed = when {
        isListening -> 600
        isSpeaking -> 800
        else -> 1500
    }
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(pulseSpeed, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (isActive) 500 else 2000, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "glow"
    )

    val expansion by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(if (isActive) 700 else 3000, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "expansion"
    )

    val outerRingScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            tween(1200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "outerRing"
    )

    val errorFlash by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(300, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "errorFlash"
    )

    val ring1Particles = remember { generateParticles(28, 0.32f, 0.42f, 0.6f) }
    val ring2Particles = remember { generateParticles(36, 0.44f, 0.54f, 0.9f) }
    val ring3Particles = remember { generateParticles(20, 0.58f, 0.68f, 0.5f) }
    val outerParticles = remember { generateParticles(16, 0.72f, 0.82f, 0.4f) }

    Canvas(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
    ) {
        val cx = center.x
        val cy = center.y
        val maxRadius = size.toPx() / 2f
        val baseRadius = maxRadius * 0.38f

        // Deep background glow
        val bgGlowRadius = baseRadius * 1.8f * expansion
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BlueDeep.copy(alpha = 0.6f * glowIntensity),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = bgGlowRadius
            ),
            radius = bgGlowRadius,
            center = Offset(cx, cy)
        )

        // Outer glow arcs
        drawOuterGlowRing(cx, cy, baseRadius * outerRingScale, CyanGlow, 0.15f * glowIntensity)
        drawOuterGlowRing(cx, cy, baseRadius * outerRingScale * 1.15f, CyanDark, 0.08f * glowIntensity)

        // Particle ring 3 (outermost, slowest)
        rotate(ring3Rotation, Offset(cx, cy)) {
            drawParticleRing(cx, cy, baseRadius * expansion, ring3Particles, 0.45f)
        }

        // Particle ring 2 (middle, counter-rotate)
        rotate(ring2Rotation, Offset(cx, cy)) {
            drawParticleRing(cx, cy, baseRadius * expansion, ring2Particles, 0.7f)
        }

        // Particle ring 1 (inner, fastest)
        rotate(ring1Rotation, Offset(cx, cy)) {
            drawParticleRing(cx, cy, baseRadius * expansion * 0.85f, ring1Particles, 0.85f)
        }

        // Outer floating particles
        rotate(ring1Rotation * 0.3f, Offset(cx, cy)) {
            drawParticleRing(cx, cy, baseRadius * outerRingScale, outerParticles, 0.35f)
        }

        // Core orb
        val coreRadius = baseRadius * 0.22f * pulse
        val coreColor = when {
            isError -> Color(0xFFFF1744).copy(alpha = 0.8f + errorFlash * 0.2f)
            isSpeaking -> CyanGlow
            isListening -> CyanBright
            else -> CyanDark.copy(alpha = 0.6f + pulse * 0.3f)
        }

        // Core glow layers
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    coreColor.copy(alpha = 0.0f),
                    coreColor.copy(alpha = 0.15f * glowIntensity),
                    coreColor.copy(alpha = 0.4f * glowIntensity),
                    coreColor
                ),
                center = Offset(cx, cy),
                radius = coreRadius * 2.5f
            ),
            radius = coreRadius * 2.5f,
            center = Offset(cx, cy)
        )

        // Core solid
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    WhiteHot.copy(alpha = 0.9f),
                    coreColor,
                    coreColor.copy(alpha = 0.8f)
                ),
                center = Offset(cx, cy),
                radius = coreRadius
            ),
            radius = coreRadius,
            center = Offset(cx, cy)
        )

        // Bright center highlight
        drawCircle(
            color = WhiteHot.copy(alpha = 0.7f),
            radius = coreRadius * 0.35f,
            center = Offset(cx, cy)
        )
    }
}

private fun DrawScope.drawParticleRing(
    cx: Float,
    cy: Float,
    radius: Float,
    particles: List<ParticleData>,
    maxAlpha: Float
) {
    for (particle in particles) {
        val r = radius * particle.radiusFactor
        val px = cx + cos(particle.angleRad) * r
        val py = cy + sin(particle.angleRad) * r
        val alpha = particle.alpha * maxAlpha
        val particleColor = particle.color.copy(alpha = alpha)

        if (particle.size > 1.5f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        particleColor.copy(alpha = alpha * 0.4f),
                        Color.Transparent
                    ),
                    center = Offset(px, py),
                    radius = particle.size * 3f
                ),
                radius = particle.size * 3f,
                center = Offset(px, py)
            )
        }

        drawCircle(
            color = particleColor,
            radius = particle.size,
            center = Offset(px, py)
        )
    }
}

private fun DrawScope.drawOuterGlowRing(
    cx: Float,
    cy: Float,
    radius: Float,
    color: Color,
    alpha: Float
) {
    drawCircle(
        color = color.copy(alpha = alpha),
        radius = radius,
        center = Offset(cx, cy),
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 2.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    )
}

data class ParticleData(
    val angleRad: Float,
    val radiusFactor: Float,
    val size: Float,
    val alpha: Float,
    val color: Color
)

private fun generateParticles(
    count: Int,
    minRadiusFactor: Float,
    maxRadiusFactor: Float,
    maxSize: Float
): List<ParticleData> {
    val random = Random(count)
    val palette = listOf(CyanBright, CyanGlow, CyanDark, PurpleMist, TealMist, WhiteHot)
    return List(count) {
        ParticleData(
            angleRad = random.nextFloat() * 2f * Math.PI.toFloat(),
            radiusFactor = minRadiusFactor + random.nextFloat() * (maxRadiusFactor - minRadiusFactor),
            size = 1f + random.nextFloat() * maxSize,
            alpha = 0.3f + random.nextFloat() * 0.7f,
            color = palette[random.nextInt(palette.size)]
        )
    }
}
