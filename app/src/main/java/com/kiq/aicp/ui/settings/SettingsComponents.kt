// app/src/main/java/com/kiq/aicp/ui/settings/SettingsComponents.kt
// 设置页的通用行组件。
//
// SliderRow 有个刻意的设计：拖动过程中只改本地 state，松手（onValueChangeFinished）才回写。
// 不这么做的话每一帧都会往 DataStore 写一次盘，既卡又费电。

package com.kiq.aicp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SectionCard(
	title: String,
	subtitle: String? = null,
	content: @Composable () -> Unit,
) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
	) {
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
				)
				subtitle?.let {
					Text(
						text = it,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.outline,
					)
				}
			}
			content()
		}
	}
}

@Composable
fun SwitchRow(
	title: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	subtitle: String? = null,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(title, style = MaterialTheme.typography.bodyLarge)
			subtitle?.let {
				Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
			}
		}
		Spacer(Modifier.width(12.dp))
		Switch(checked = checked, onCheckedChange = onCheckedChange)
	}
}

@Composable
fun SliderRow(
	title: String,
	value: Int,
	valueRange: IntRange,
	step: Int,
	onValueSettled: (Int) -> Unit,
	subtitle: String? = null,
	valueLabel: (Int) -> String = { it.toString() },
) {
	// key 用 value：外部（比如"恢复默认"）改了值，本地显示要跟着重置
	var local by remember(value) { mutableIntStateOf(value) }

	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
			Text(
				text = valueLabel(local),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.primary,
			)
		}
		subtitle?.let {
			Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
		}
		Slider(
			value = local.toFloat(),
			onValueChange = { raw ->
				local = snapToStep(raw, valueRange, step)
			},
			onValueChangeFinished = { onValueSettled(local) },
			valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
			steps = stepsBetween(valueRange, step),
		)
	}
}

private fun snapToStep(raw: Float, range: IntRange, step: Int): Int {
	val offset = ((raw - range.first) / step).roundToInt() * step
	return (range.first + offset).coerceIn(range.first, range.last)
}

/** Slider 的 steps 指的是中间刻度数，所以要减 1；不足一格时给 0 */
private fun stepsBetween(range: IntRange, step: Int): Int {
	val count = (range.last - range.first) / step - 1
	return count.coerceAtLeast(0)
}
