package analyzer

import java.nio.file.Path
import kotlin.io.path.readText

val EXAMPLES = listOf("INC-201", "INC-202", "INC-203", "INC-204", "INC-205")

fun example(id: String): String = Path.of("examples/$id.txt").readText()
