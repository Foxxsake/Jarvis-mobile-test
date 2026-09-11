package com.example.engine.phone

import com.example.engine.CommandAction
import com.example.engine.CommandCategory
import com.example.engine.CommandProposal
import com.example.engine.PlannedAction
import com.example.engine.termux.TermuxRiskLevel

/**
 * Parses natural language voice commands into phone control PlannedActions.
 * Handles volume, brightness, WiFi, Bluetooth, flashlight, media, alarm, timer,
 * screenshot, URL opening, web search, camera, and Do Not Disturb commands.
 */
object PhoneCommandParser {

    data class PhoneParseResult(
        val action: CommandAction,
        val category: CommandCategory = CommandCategory.DEVICE_ACTION,
        val rawArguments: String? = null,
        val targetAppOrPerson: String? = null,
        val messageOrQuery: String? = null,
        val requiresApproval: Boolean = false,
        val riskLevel: TermuxRiskLevel? = null,
        val proposal: CommandProposal? = null
    )

    fun parse(text: String): PhoneParseResult? {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        return parseVolume(lower, trimmed)
            ?: parseBrightness(lower, trimmed)
            ?: parseWifi(lower, trimmed)
            ?: parseBluetooth(lower, trimmed)
            ?: parseFlashlight(lower, trimmed)
            ?: parseMedia(lower, trimmed)
            ?: parseAlarm(lower, trimmed)
            ?: parseTimer(lower, trimmed)
            ?: parseScreenshot(lower, trimmed)
            ?: parseSearch(lower, trimmed)
            ?: parseOpenUrl(lower, trimmed)
            ?: parsePhoto(lower, trimmed)
            ?: parseDoNotDisturb(lower, trimmed)
    }

    private fun parseVolume(lower: String, trimmed: String): PhoneParseResult? {
        // "mute"
        if (lower == "mute") {
            return PhoneParseResult(
                action = CommandAction.SET_VOLUME,
                rawArguments = "mute",
                requiresApproval = false
            )
        }

        // "unmute"
        if (lower == "unmute") {
            return PhoneParseResult(
                action = CommandAction.SET_VOLUME,
                rawArguments = "unmute",
                requiresApproval = false
            )
        }

        // "volume up" / "turn up volume" / "increase volume" / "raise volume"
        if (lower.matches(Regex("^(volume up|turn up volume|increase volume|raise volume|louder|volume louder|make it louder|turn the volume up|increase the volume|raise the volume)$"))) {
            return PhoneParseResult(
                action = CommandAction.SET_VOLUME,
                rawArguments = "up",
                requiresApproval = false
            )
        }

        // "volume down" / "turn down volume" / "decrease volume" / "lower volume"
        if (lower.matches(Regex("^(volume down|turn down volume|decrease volume|lower volume|quieter|volume quieter|make it quieter|turn the volume down|decrease the volume|lower the volume|softer|make it softer)$"))) {
            return PhoneParseResult(
                action = CommandAction.SET_VOLUME,
                rawArguments = "down",
                requiresApproval = false
            )
        }

        // "set volume to 50" / "volume to 50" / "volume 50" / "set volume 50 percent"
        val volumePattern = Regex("^(?:set )?volume (?:to )?(\\d{1,3})(?:\\s*%|\\s*percent)?$")
        volumePattern.find(lower)?.let { match ->
            val level = match.groupValues[1].toIntOrNull() ?: return null
            return PhoneParseResult(
                action = CommandAction.SET_VOLUME,
                rawArguments = level.toString(),
                requiresApproval = false
            )
        }

        // "volume at 50 percent"
        val volumeAtPattern = Regex("^volume (?:at|to) (\\d{1,3})(?:\\s*%|\\s*percent)?$")
        volumeAtPattern.find(lower)?.let { match ->
            val level = match.groupValues[1].toIntOrNull() ?: return null
            return PhoneParseResult(
                action = CommandAction.SET_VOLUME,
                rawArguments = level.toString(),
                requiresApproval = false
            )
        }

        return null
    }

    private fun parseBrightness(lower: String, trimmed: String): PhoneParseResult? {
        // "brightness up" / "turn up brightness" / "increase brightness" / "brighter"
        if (lower.matches(Regex("^(brightness up|turn up brightness|increase brightness|raise brightness|brighter|make it brighter|turn the brightness up|increase the brightness|brighten)$"))) {
            return PhoneParseResult(
                action = CommandAction.SET_BRIGHTNESS,
                rawArguments = "up",
                requiresApproval = false
            )
        }

        // "brightness down" / "turn down brightness" / "decrease brightness" / "dimmer"
        if (lower.matches(Regex("^(brightness down|turn down brightness|decrease brightness|lower brightness|dimmer|make it dimmer|dim the screen|turn the brightness down|decrease the brightness|dimmer)$"))) {
            return PhoneParseResult(
                action = CommandAction.SET_BRIGHTNESS,
                rawArguments = "down",
                requiresApproval = false
            )
        }

        // "set brightness to 50" / "brightness to 50" / "brightness 50"
        val brightnessPattern = Regex("^(?:set )?brightness (?:to )?(\\d{1,3})(?:\\s*%|\\s*percent)?$")
        brightnessPattern.find(lower)?.let { match ->
            val level = match.groupValues[1].toIntOrNull() ?: return null
            return PhoneParseResult(
                action = CommandAction.SET_BRIGHTNESS,
                rawArguments = level.toString(),
                requiresApproval = false
            )
        }

        // "dim the screen to 30" / "screen brightness 70"
        val screenBrightnessPattern = Regex("^(?:dim |set )?the screen(?: brightness)? (?:to )?(\\d{1,3})(?:\\s*%|\\s*percent)?$")
        screenBrightnessPattern.find(lower)?.let { match ->
            val level = match.groupValues[1].toIntOrNull() ?: return null
            return PhoneParseResult(
                action = CommandAction.SET_BRIGHTNESS,
                rawArguments = level.toString(),
                requiresApproval = false
            )
        }

        return null
    }

    private fun parseWifi(lower: String, trimmed: String): PhoneParseResult? {
        // "turn on wifi" / "enable wifi" / "wifi on"
        if (lower.matches(Regex("^(turn on wifi|enable wifi|wifi on|switch on wifi|activate wifi|turn wifi on|enable the wifi|turn on the wifi)$"))) {
            return PhoneParseResult(
                action = CommandAction.TOGGLE_WIFI,
                rawArguments = "on",
                requiresApproval = false
            )
        }

        // "turn off wifi" / "disable wifi" / "wifi off"
        if (lower.matches(Regex("^(turn off wifi|disable wifi|wifi off|switch off wifi|deactivate wifi|turn wifi off|disable the wifi|turn off the wifi)$"))) {
            return PhoneParseResult(
                action = CommandAction.TOGGLE_WIFI,
                rawArguments = "off",
                requiresApproval = false
            )
        }

        // "toggle wifi"
        if (lower == "toggle wifi" || lower == "switch wifi") {
            return PhoneParseResult(
                action = CommandAction.TOGGLE_WIFI,
                rawArguments = "toggle",
                requiresApproval = false
            )
        }

        return null
    }

    private fun parseBluetooth(lower: String, trimmed: String): PhoneParseResult? {
        // "turn on bluetooth" / "enable bluetooth"
        if (lower.matches(Regex("^(turn on bluetooth|enable bluetooth|bluetooth on|switch on bluetooth|activate bluetooth|turn bluetooth on|enable the bluetooth|turn on the bluetooth)$"))) {
            return PhoneParseResult(
                action = CommandAction.TOGGLE_BLUETOOTH,
                rawArguments = "on",
                requiresApproval = false
            )
        }

        // "turn off bluetooth" / "disable bluetooth"
        if (lower.matches(Regex("^(turn off bluetooth|disable bluetooth|bluetooth off|switch off bluetooth|deactivate bluetooth|turn bluetooth off|disable the bluetooth|turn off the bluetooth)$"))) {
            return PhoneParseResult(
                action = CommandAction.TOGGLE_BLUETOOTH,
                rawArguments = "off",
                requiresApproval = false
            )
        }

        // "toggle bluetooth"
        if (lower == "toggle bluetooth" || lower == "switch bluetooth") {
            return PhoneParseResult(
                action = CommandAction.TOGGLE_BLUETOOTH,
                rawArguments = "toggle",
                requiresApproval = false
            )
        }

        return null
    }

    private fun parseFlashlight(lower: String, trimmed: String): PhoneParseResult? {
        // "turn on flashlight" / "flashlight on" / "torch on"
        if (lower.matches(Regex("^(turn on flashlight|flashlight on|torch on|switch on flashlight|turn the flashlight on|turn flashlight on|flash on|turn on the torch|torch on|light on)$"))) {
            return PhoneParseResult(
                action = CommandAction.TOGGLE_FLASHLIGHT,
                rawArguments = "on",
                requiresApproval = false
            )
        }

        // "turn off flashlight" / "flashlight off" / "torch off"
        if (lower.matches(Regex("^(turn off flashlight|flashlight off|torch off|switch off flashlight|turn the flashlight off|turn flashlight off|flash off|turn off the torch|torch off|light off)$"))) {
            return PhoneParseResult(
                action = CommandAction.TOGGLE_FLASHLIGHT,
                rawArguments = "off",
                requiresApproval = false
            )
        }

        // "toggle flashlight" / "flashlight"
        if (lower == "toggle flashlight" || lower == "flashlight" || lower == "toggle torch" || lower == "torch") {
            return PhoneParseResult(
                action = CommandAction.TOGGLE_FLASHLIGHT,
                rawArguments = "toggle",
                requiresApproval = false
            )
        }

        return null
    }

    private fun parseMedia(lower: String, trimmed: String): PhoneParseResult? {
        // Play
        if (lower.matches(Regex("^(play music|play|resume|resume music|start playing|unpause|continue playing|play the music|resume the music)$"))) {
            return PhoneParseResult(
                action = CommandAction.PLAY_MEDIA,
                rawArguments = trimmed,
                requiresApproval = false
            )
        }

        // Pause
        if (lower.matches(Regex("^(pause|pause music|stop music|pause the music|stop playing|pause playing|halt)$"))) {
            return PhoneParseResult(
                action = CommandAction.PAUSE_MEDIA,
                rawArguments = trimmed,
                requiresApproval = false
            )
        }

        // Next track
        if (lower.matches(Regex("^(next track|next song|skip track|skip song|skip|next|next one|forward|go to next)$"))) {
            return PhoneParseResult(
                action = CommandAction.NEXT_TRACK,
                rawArguments = trimmed,
                requiresApproval = false
            )
        }

        // Previous track
        if (lower.matches(Regex("^(previous track|previous song|go back|last track|last song|previous one|back track|rewind)$"))) {
            return PhoneParseResult(
                action = CommandAction.PREV_TRACK,
                rawArguments = trimmed,
                requiresApproval = false
            )
        }

        return null
    }

    private fun parseAlarm(lower: String, trimmed: String): PhoneParseResult? {
        // "set alarm for 7am" / "set alarm at 7:30" / "wake me up at 7" / "alarm at 7am"
        val alarmPattern = Regex(
            "^(?:set )?alarm (?:for |at )?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$",
            RegexOption.IGNORE_CASE
        )
        alarmPattern.find(trimmed)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase()
            val adjustedHour = when {
                ampm == "pm" && hour < 12 -> hour + 12
                ampm == "am" && hour == 12 -> 0
                else -> hour
            }
            val timeStr = String.format("%02d:%02d", adjustedHour, minute)
            return PhoneParseResult(
                action = CommandAction.SET_ALARM,
                rawArguments = "$adjustedHour:$minute",
                requiresApproval = false,
                proposal = CommandProposal(
                    tool = "System Alarm",
                    workspace = "Device",
                    command = "set alarm for $timeStr",
                    riskLevel = TermuxRiskLevel.READ_ONLY,
                    reason = "Set system alarm"
                )
            )
        }

        // "wake me up at 7:30am"
        val wakePattern = Regex(
            "^(?:wake me up|wake me) (?:at|for) (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$",
            RegexOption.IGNORE_CASE
        )
        wakePattern.find(trimmed)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase()
            val adjustedHour = when {
                ampm == "pm" && hour < 12 -> hour + 12
                ampm == "am" && hour == 12 -> 0
                else -> hour
            }
            val timeStr = String.format("%02d:%02d", adjustedHour, minute)
            return PhoneParseResult(
                action = CommandAction.SET_ALARM,
                rawArguments = "$adjustedHour:$minute",
                requiresApproval = false,
                proposal = CommandProposal(
                    tool = "System Alarm",
                    workspace = "Device",
                    command = "set alarm for $timeStr",
                    riskLevel = TermuxRiskLevel.READ_ONLY,
                    reason = "Set wake-up alarm"
                )
            )
        }

        return null
    }

    private fun parseTimer(lower: String, trimmed: String): PhoneParseResult? {
        // "set timer for 10 minutes" / "timer 5 minutes" / "countdown 30 seconds"
        val timerPattern = Regex(
            "^(?:set )?(?:timer|countdown|count down) (?:for )?(\\d+)\\s*(seconds?|minutes?|mins?|hours?|hrs?)?$",
            RegexOption.IGNORE_CASE
        )
        timerPattern.find(trimmed)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return null
            val unit = match.groupValues[2].lowercase().let {
                when {
                    it.startsWith("hour") || it.startsWith("hr") -> "hours"
                    it.startsWith("min") || it == "min" -> "minutes"
                    else -> "seconds"
                }
            }
            val durationSeconds = when (unit) {
                "hours" -> amount * 3600
                "minutes" -> amount * 60
                else -> amount
            }
            val unitStr = if (amount == 1) unit.removeSuffix("s") else unit
            return PhoneParseResult(
                action = CommandAction.SET_TIMER,
                rawArguments = durationSeconds.toString(),
                requiresApproval = false,
                proposal = CommandProposal(
                    tool = "System Timer",
                    workspace = "Device",
                    command = "set timer for $amount $unitStr",
                    riskLevel = TermuxRiskLevel.READ_ONLY,
                    reason = "Set countdown timer"
                )
            )
        }

        // "timer 10m" / "timer 30s" / "timer 1h"
        val timerShorthandPattern = Regex(
            "^(?:set )?(?:timer|countdown) (\\d+)([smh])$",
            RegexOption.IGNORE_CASE
        )
        timerShorthandPattern.find(trimmed)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return null
            val unit = match.groupValues[2].lowercase()
            val durationSeconds = when (unit) {
                "h" -> amount * 3600
                "m" -> amount * 60
                else -> amount
            }
            val unitStr = when (unit) {
                "h" -> if (amount == 1) "hour" else "hours"
                "m" -> if (amount == 1) "minute" else "minutes"
                else -> if (amount == 1) "second" else "seconds"
            }
            return PhoneParseResult(
                action = CommandAction.SET_TIMER,
                rawArguments = durationSeconds.toString(),
                requiresApproval = false,
                proposal = CommandProposal(
                    tool = "System Timer",
                    workspace = "Device",
                    command = "set timer for $amount $unitStr",
                    riskLevel = TermuxRiskLevel.READ_ONLY,
                    reason = "Set countdown timer"
                )
            )
        }

        return null
    }

    private fun parseScreenshot(lower: String, trimmed: String): PhoneParseResult? {
        if (lower.matches(Regex("^(take a screenshot|screenshot|capture screen|screen capture|take screenshot|snap|take a snap|capture the screen|grab the screen)$"))) {
            return PhoneParseResult(
                action = CommandAction.SCREENSHOT,
                rawArguments = trimmed,
                requiresApproval = false
            )
        }
        return null
    }

    private fun parseSearch(lower: String, trimmed: String): PhoneParseResult? {
        // "search for restaurants" / "google restaurants" / "look up weather"
        val searchPattern = Regex(
            "^(?:search(?:\\s+for)?|google|look up|find|search the web for|look up on the web)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )
        searchPattern.find(trimmed)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.isNotBlank()) {
                return PhoneParseResult(
                    action = CommandAction.SEARCH_WEB,
                    rawArguments = query,
                    messageOrQuery = query,
                    requiresApproval = false
                )
            }
        }
        return null
    }

    private fun parseOpenUrl(lower: String, trimmed: String): PhoneParseResult? {
        // "open google.com" / "go to example.com" / "open website"
        val urlPattern = Regex(
            "^(?:open|go to|navigate to|visit|launch)\\s+(https?://\\S+|\\S+\\.\\S+\\.(?:com|org|net|io|dev|co|app|edu|gov))$",
            RegexOption.IGNORE_CASE
        )
        urlPattern.find(trimmed)?.let { match ->
            val url = match.groupValues[1].trim()
            return PhoneParseResult(
                action = CommandAction.OPEN_URL,
                rawArguments = url,
                requiresApproval = false
            )
        }
        return null
    }

    private fun parsePhoto(lower: String, trimmed: String): PhoneParseResult? {
        if (lower.matches(Regex("^(take a photo|take a picture|take photo|take picture|open camera|start camera|launch camera|camera|selfie|take a selfie)$"))) {
            return PhoneParseResult(
                action = CommandAction.TAKE_PHOTO,
                rawArguments = trimmed,
                requiresApproval = false
            )
        }
        return null
    }

    private fun parseDoNotDisturb(lower: String, trimmed: String): PhoneParseResult? {
        // "turn on do not disturb" / "enable dnd" / "dnd on"
        if (lower.matches(Regex("^(turn on do not disturb|enable do not disturb|dnd on|do not disturb on|activate do not disturb|silence notifications|quiet mode on|enable dnd|turn on dnd)$"))) {
            return PhoneParseResult(
                action = CommandAction.DO_NOT_DISTURB_ON,
                rawArguments = trimmed,
                requiresApproval = false
            )
        }

        // "turn off do not disturb" / "disable dnd" / "dnd off"
        if (lower.matches(Regex("^(turn off do not disturb|disable do not disturb|dnd off|do not disturb off|deactivate do not disturb|disable dnd|turn off dnd|unsilence notifications|quiet mode off)$"))) {
            return PhoneParseResult(
                action = CommandAction.DO_NOT_DISTURB_OFF,
                rawArguments = trimmed,
                requiresApproval = false
            )
        }

        return null
    }

    /**
     * Creates a PlannedAction from a PhoneParseResult.
     */
    fun toPlannedAction(result: PhoneParseResult): PlannedAction {
        return PlannedAction(
            action = result.action,
            category = result.category,
            targetAppOrPerson = result.targetAppOrPerson,
            rawArguments = result.rawArguments,
            messageOrQuery = result.messageOrQuery,
            requiresApproval = result.requiresApproval,
            riskLevel = result.riskLevel,
            proposal = result.proposal
        )
    }
}
