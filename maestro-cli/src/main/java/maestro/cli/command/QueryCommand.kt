/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.ElementFilter
import maestro.Filters
import maestro.Filters.asFilter
import maestro.cli.App
import maestro.cli.DisableAnsiMixin
import maestro.cli.session.MaestroSessionManager
import maestro.cli.view.red
import maestro.orchestra.Orchestra
import maestro.utils.StringUtils.toRegexSafe
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model
import picocli.CommandLine.Option
import picocli.CommandLine.Spec

@Command(
    name = "query",
    description = [
        "Find elements in the view hierarchy of the connected device"
    ],
    hidden = true
)
class QueryCommand : Runnable {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @Option(names = ["text"])
    private var text: String? = null

    @Option(names = ["id"])
    private var id: String? = null

    @Spec
    lateinit var commandSpec: Model.CommandSpec

    override fun run() {
        MaestroSessionManager.newSession(parent?.host, parent?.port, parent?.deviceId) { session ->
            val filters = mutableListOf<ElementFilter>()

            text?.let {
                filters += Filters.textMatches(it.toRegexSafe(Orchestra.REGEX_OPTIONS))
            }

            id?.let {
                filters += Filters.idMatches(it.toRegexSafe(Orchestra.REGEX_OPTIONS))
            }

            if (filters.isEmpty()) {
                throw CommandLine.ParameterException(
                    commandSpec.commandLine(),
                    "Must specify at least one search criteria"
                )
            }

            val elements = session.maestro.allElementsMatching(
                Filters.intersect(filters)
            )

            val mapper = jacksonObjectMapper()
                .writerWithDefaultPrettyPrinter()

            println("Matches: ${elements.size}")
            elements.forEach {
                println(
                    mapper.writeValueAsString(it)
                )
            }
        }
        System.err.println("This command is deprecated. Use \"maestro studio\" instead.".red())
    }

}
