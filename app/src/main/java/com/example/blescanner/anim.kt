package com.example.blescanner

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Data class to represent a confetti particle
data class ConfettiParticle(
    val color: Color,
    val startPosition: Offset,
    val size: Float,
    val speed: Float = Random.nextFloat() * 2 + 1f,
    val angle: Float = Random.nextFloat() * 360f,
    val rotationSpeed: Float = Random.nextFloat() * 10 - 5
)

@Composable
fun ConfettiAnimation(
    isActive: Boolean,
    colors: List<Color> = listOf(
        Color(0xFFFFC107), // Yellow
        Color(0xFF2196F3), // Blue
        Color(0xFFE91E63), // Pink
        Color(0xFF4CAF50), // Green
        Color(0xFFFF5722)  // Orange
    ),
    particleCount: Int = 100,
    durationMillis: Int = 2000
) {
    // Only create particles when isActive changes to true
    var particles by remember { mutableStateOf<List<ConfettiParticle>>(emptyList()) }

    // Animation progress (0f to 1f)
    val animationProgress = remember { Animatable(0f) }

    LaunchedEffect(isActive) {
        if (isActive) {
            // Create new particles when animation starts
            particles = List(particleCount) {
                ConfettiParticle(
                    color = colors[Random.nextInt(colors.size)],
                    startPosition = Offset(
                        x = Random.nextFloat() * 0.1f + 0.45f, // Center mostly
                        y = 0.3f
                    ),
                    size = Random.nextFloat() * 15f + 5f,
                    speed = Random.nextFloat() * 2f + 0.5f
                )
            }

            // Reset and start animation
            animationProgress.snapTo(0f)
            animationProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMillis,
                    easing = EaseOutQuint
                )
            )
        }
    }

    // Only draw confetti when animation is active
    if (isActive && particles.isNotEmpty()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            particles.forEach { particle ->
                val progress = animationProgress.value

                // Calculate current position based on time, angle and speed
                val gravity = 1f
                val radians = Math.toRadians(particle.angle.toDouble()).toFloat()
                val speedX = cos(radians) * particle.speed
                val speedY = sin(radians) * particle.speed + (gravity * progress * 8)

                val posX = particle.startPosition.x * canvasWidth + speedX * progress * canvasHeight
                val posY = particle.startPosition.y * canvasHeight + speedY * progress * canvasHeight

                // Fade out towards the end
                val alpha = 1f - (progress * 1.2f).coerceAtMost(1f)

                // Draw the confetti particle with rotation
                rotate(
                    degrees = particle.rotationSpeed * progress * 360f,
                    pivot = Offset(posX, posY)
                ) {
                    drawRect(
                        color = particle.color.copy(alpha = alpha),
                        topLeft = Offset(posX - particle.size / 2, posY - particle.size / 2),
                        size = androidx.compose.ui.geometry.Size(particle.size, particle.size * 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun SuccessConfetti(isActive: Boolean) {
    // Confetti with academic/professional colors
    val confettiColors = listOf(
        Color(0xFF1A2151), // Dark blue (primary app color)
        Color(0xFF43A047), // Green (success color)
        Color(0xFF0D47A1), // Accent blue
        Color(0xFFFFA000), // Amber (warm accent)
        Color(0xFFFFFFFF)  // White
    )

    Box(modifier = Modifier.fillMaxSize()) {
        ConfettiAnimation(
            isActive = isActive,
            colors = confettiColors,
            particleCount = 120,
            durationMillis = 2500
        )
    }
}