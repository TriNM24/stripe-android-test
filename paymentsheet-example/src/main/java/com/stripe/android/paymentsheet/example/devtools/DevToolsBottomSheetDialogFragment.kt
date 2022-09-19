package com.stripe.android.paymentsheet.example.devtools

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.LeadingIconTab
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.PagerState
import com.google.accompanist.pager.rememberPagerState
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.stripe.android.core.networking.StripeNetworkClientInterceptor
import kotlinx.coroutines.launch

class DevToolsBottomSheetDialogFragment : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                DevTools()
            }
        }
    }

    companion object {
        fun newInstance() = DevToolsBottomSheetDialogFragment()
    }
}

private enum class DevToolsTab {
    Network,
    FeatureFlags;
}

@Composable
private fun DevToolsTab.Content() {
    when (this) {
        DevToolsTab.Network -> DevToolsNetwork()
        DevToolsTab.FeatureFlags -> Text("Coming… someday?")
    }
}

@OptIn(ExperimentalPagerApi::class)
@Composable
private fun DevTools() {
    val tabs = DevToolsTab.values()
    val pagerState = rememberPagerState()

    Scaffold(
        topBar = {
            Surface(elevation = 4.dp) {
                Column {
                    TopAppBar(
                        title = { Text("DevTools") },
                        elevation = 0.dp
                    )
                    DevToolsTabRow(
                        tabs = tabs,
                        pagerState = pagerState
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            count = tabs.size,
            modifier = Modifier.padding(padding)
        ) { page ->
            tabs[page].Content()
        }
    }
}

@OptIn(ExperimentalPagerApi::class)
@Composable
private fun DevToolsTabRow(
    tabs: Array<DevToolsTab>,
    pagerState: PagerState,
) {
    val scope = rememberCoroutineScope()
    TabRow(
        selectedTabIndex = pagerState.currentPage
    ) {
        for ((index, tab) in tabs.withIndex()) {
            LeadingIconTab(
                text = { Text(tab.name) },
                icon = {},
                selected = pagerState.currentPage == index,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(index)
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DevToolsNetwork() {
    val endpoints = remember { DevToolsStore.endpoints }

    LazyColumn(
        modifier = Modifier.nestedScroll(rememberNestedScrollInteropConnection())
    ) {
        item {
            Text(
                text = "Fail requests to endpoints",
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(16.dp)
            )
        }

        item {
            ErrorTypeDropdown()
        }

        itemsIndexed(endpoints) { index, endpoint ->
            DevToolsEndpointItem(
                endpoint = endpoint,
                isLastItem = index == endpoints.lastIndex,
                onToggle = { DevToolsStore.toggleFailureFor(endpoint) }
            )
        }
    }
}

@Composable
private fun ErrorTypeDropdown() {
    var isExpanded by remember { mutableStateOf(false) }

    val items = StripeNetworkClientInterceptor.ErrorType.values()
    val selected = StripeNetworkClientInterceptor.errorType

    Box(modifier = Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { isExpanded = true }) {
            Text(text = "Throws: ${selected.name}")
        }

        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            for (item in items) {
                DropdownMenuItem(
                    onClick = {
                        StripeNetworkClientInterceptor.setErrorToThrow(item)
                        isExpanded = false
                    }
                ) {
                    Text(
                        text = item.name,
                        color = if (item == selected) {
                            MaterialTheme.colors.primary
                        } else {
                            Color.Unspecified
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DevToolsEndpointItem(
    endpoint: Endpoint,
    isLastItem: Boolean,
    onToggle: () -> Unit
) {
    val isTurnedOn = endpoint in DevToolsStore.failingEndpoints

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val title = remember(endpoint.name) {
            endpoint.name.buildAnnotatedUrl()
        }

        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Switch(
            checked = isTurnedOn,
            onCheckedChange = { onToggle() }
        )
    }

    if (!isLastItem) {
        Divider()
    }
}

private fun String.buildAnnotatedUrl(): AnnotatedString {
    val parts = split("{", "}")
    val paramParts = parts.filterIndexed { index, _ -> index % 2 == 1 }

    return buildAnnotatedString {
        for (part in parts) {
            val color = if (part in paramParts) Color.Gray else Color.Unspecified
            withStyle(style = SpanStyle(color = color)) {
                append(part)
            }
        }
    }
}
