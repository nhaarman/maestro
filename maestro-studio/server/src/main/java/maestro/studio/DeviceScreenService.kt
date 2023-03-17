package maestro.studio

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.call
import io.ktor.server.http.content.files
import io.ktor.server.http.content.static
import io.ktor.server.http.content.staticRootFolder
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import maestro.ElementFilter
import maestro.Filters
import maestro.Filters.asFilter
import maestro.Maestro
import maestro.TreeNode
import maestro.orchestra.Orchestra
import maestro.utils.StringUtils.toRegexSafe
import java.io.File
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.UUID
import java.util.regex.Pattern
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory

object DeviceScreenService {

    private const val MAX_SCREENSHOTS = 10

    private val SCREENSHOT_DIR = getScreenshotDir()

    private val savedScreenshots = mutableListOf<File>()

    fun routes(routing: Routing, maestro: Maestro) {
        routing.get("/api/device-screen") {
            val tree: TreeNode
            val screenshotFile: File
            synchronized(DeviceScreenService) {
                tree = maestro.viewHierarchy().root
                screenshotFile = takeScreenshot(maestro)
                savedScreenshots.add(screenshotFile)
                while (savedScreenshots.size > MAX_SCREENSHOTS) {
                    savedScreenshots.removeFirst().delete()
                }
            }

            val deviceInfo = maestro.deviceInfo()

            val deviceWidth = deviceInfo.widthGrid
            val deviceHeight = deviceInfo.heightGrid

            val elements = treeToElements(tree)
            val deviceScreen = DeviceScreen("/screenshot/${screenshotFile.name}", deviceWidth, deviceHeight, elements)
            val response = jacksonObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(deviceScreen)
            call.respondText(response)
        }
        routing.static("/screenshot") {
            staticRootFolder = SCREENSHOT_DIR.toFile()
            files(".")
        }
    }
    private fun treeToElements(tree: TreeNode): List<UIElement> {
        fun gatherElements(tree: TreeNode, list: MutableList<TreeNode>): List<TreeNode> {
            tree.children.forEach { child ->
                gatherElements(child, list)
            }
            list.add(tree)
            return list
        }

        fun TreeNode.attribute(key: String): String? {
            val value = attributes[key]
            if (value.isNullOrEmpty()) return null
            return value
        }

        val elements = gatherElements(tree, mutableListOf())
            .sortedWith(Filters.INDEX_COMPARATOR)

        fun getIndex(filter: ElementFilter, element: TreeNode): Int? {
            val identityHashMap = IdentityHashMap<TreeNode, Unit>()
            val matchingElements = Filters.deepestMatchingElement(filter)(elements).filter {
                // There are duplicate elements for some reason (likely due to unintended behavior in Filter.deepestMatchingElement) - filter them out
                identityHashMap.put(it, Unit) == null
            }
            if (matchingElements.size < 2) return null
            return matchingElements.sortedWith(Filters.INDEX_COMPARATOR).indexOf(element)
        }

        val ids = mutableMapOf<String, Int>()
        return elements.map { element ->
            val bounds = element.bounds()
            val text = element.attribute("text")
            val hintText = element.attribute("hintText")
            val accessibilityText = element.attribute("accessibilityText")
            val resourceId = element.attribute("resource-id")
            val textIndex = if (text == null) {
                null
            } else {
                getIndex(Filters.textMatches(text.toRegexSafe(Orchestra.REGEX_OPTIONS)), element)
            }
            val resourceIdIndex = if (resourceId == null) {
                null
            } else {
                getIndex(Filters.idMatches(resourceId.toRegexSafe(Orchestra.REGEX_OPTIONS)), element)
            }
            fun createElementId(): String {
                val parts = listOfNotNull(resourceId, resourceIdIndex, text, textIndex)
                val fallbackId = bounds?.let { (x, y, w, h) -> "$x,$y,$w,$h" } ?: UUID.randomUUID().toString()
                val id = if (parts.isEmpty()) fallbackId else parts.joinToString("-")
                val index = ids.compute(id) { _, i -> (i ?: 0) + 1}
                return if (index == 1) id else "$id-$index"
            }
            val id = createElementId()
            UIElement(id, bounds, resourceId, resourceIdIndex, text, hintText, accessibilityText, textIndex)
        }
    }

    private fun TreeNode.bounds(): UIElementBounds? {
        val boundsString = attributes["bounds"] ?: return null
        val pattern = Pattern.compile("\\[([0-9-]+),([0-9-]+)]\\[([0-9-]+),([0-9-]+)]")
        val m = pattern.matcher(boundsString)
        if (!m.matches()) {
            System.err.println("Warning: Bounds text does not match expected pattern: $boundsString")
            return null
        }

        val l = m.group(1).toIntOrNull() ?: return null
        val t = m.group(2).toIntOrNull() ?: return null
        val r = m.group(3).toIntOrNull() ?: return null
        val b = m.group(4).toIntOrNull() ?: return null

        return UIElementBounds(
            x = l,
            y = t,
            width = r - l,
            height = b - t,
        )
    }

    private fun takeScreenshot(maestro: Maestro): File {
        val name = "${UUID.randomUUID()}.png"
        val screenshotFile = SCREENSHOT_DIR.resolve(name).toFile()
        screenshotFile.deleteOnExit()
        try {
            maestro.takeScreenshot(screenshotFile)
        } catch (ignore: Exception) {
            // ignore intermittent screenshot errors
        }
        return screenshotFile
    }

    private fun getScreenshotDir(): Path {
        val home = System.getProperty("user.home")
        val parent = if (home.isNullOrBlank()) createTempDirectory() else Path(home)
        val screenshotDir = parent.resolve(".maestro/studio/screenshots")
        screenshotDir.createDirectories()
        return screenshotDir
    }
}