package com.example.dollybot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dollybot.R

@Composable
internal fun ScreenBody(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
internal fun PageStatus(label: String, status: String? = null, isError: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (status != null) {
            Text(
                text = status,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                color = if (isError) colors.error else colors.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String, isError: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            color = if (isError) colors.error else colors.onSurface
        )
    }
}

@Composable
internal fun PlaceholderButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isStop: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = false,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = if (isStop) colors.errorContainer else colors.surfaceVariant,
            disabledContentColor = if (isStop) colors.onErrorContainer else colors.onSurfaceVariant
        )
    ) {
        Text(label, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun MotionFooter(
    actions: DollyBotActions,
    showStart: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showStart) {
                PlaceholderButton(
                    stringResource(R.string.start),
                    actions::startPlayback,
                    Modifier.weight(1f)
                )
            }
            PlaceholderButton(
                stringResource(R.string.stop),
                actions::stopMotion,
                modifier = Modifier.weight(1f),
                isStop = true
            )
        }
    }
}

@Composable
internal fun NavigationRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Chevron()
        }
    }
}

@Composable
internal fun Chevron(modifier: Modifier = Modifier, forward: Boolean = true) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(20.dp)) {
        val tip = Offset(size.width * if (forward) 0.64f else 0.36f, center.y)
        val otherX = size.width - tip.x
        drawLine(color, Offset(otherX, size.height * 0.24f), tip, 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, tip, Offset(otherX, size.height * 0.76f), 2.dp.toPx(), StrokeCap.Round)
    }
}
