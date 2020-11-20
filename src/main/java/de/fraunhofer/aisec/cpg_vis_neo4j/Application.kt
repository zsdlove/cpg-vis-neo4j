package de.fraunhofer.aisec.cpg_vis_neo4j

import de.fraunhofer.aisec.cpg.TranslationConfiguration
import de.fraunhofer.aisec.cpg.TranslationManager
import de.fraunhofer.aisec.cpg.TranslationResult
import de.fraunhofer.aisec.cpg.graph.Node
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.neo4j.driver.exceptions.AuthenticationException
import org.neo4j.ogm.config.Configuration
import org.neo4j.ogm.exception.ConnectionException
import org.neo4j.ogm.session.Session
import org.neo4j.ogm.session.SessionFactory
import picocli.CommandLine
import java.io.File
import java.net.ConnectException
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.Callable
import kotlin.system.exitProcess

private const val S_TO_MS_FACTOR = 1000
private const val TIME_BETWEEN_CONNECTION_TRIES = 2000
private const val MAX_COUNT_OF_FAILS = 10
private const val URI = "bolt://localhost"
private const val AUTO_INDEX = "none"
private const val VERIFY_CONNECTION = true
private const val PURGE_DB = true
private const val DEBUG_PARSER = true

/**
 * An application to export the <a href="https://github.com/Fraunhofer-AISEC/cpg">cpg</a> to
 * a <a href="https://github.com/Fraunhofer-AISEC/cpg">neo4j</a> database.
 *
 * @author Andreas Hager, andreas.hager@aisec.fraunhofer.de
 *
 */
class Application : Callable<Int> {
    private val log: Logger
    get() = LoggerFactory.getLogger(Application::class.java)

    @CommandLine.Parameters(
        arity = "1..*",
        description = [
            "The paths to analyze. If module support is enabled, the paths will be looked at if they contain modules"
        ]
    )
    private var files: Array<String> = arrayOf(".")

    @CommandLine.Option(
        names = ["--user"],
        description = ["Neo4j user name (default: neo4j)"]
    )
    private var neo4jUsername: String = "neo4j"

    @CommandLine.Option(
        names = ["--password"],
        description = ["Neo4j password (default: password"])

    private var neo4jPassword: String = "password"

    @CommandLine.Option(
        names = ["--save-depth"],
        description = [
            "Performance optimisation: " +
                "Limit recursion depth form neo4j OGM when leaving the AST. " +
                "-1 (default) means no limit is used."
        ]
    )
    private var depth: Int = -1

    @CommandLine.Option(
        names = ["--load-includes"],
        description = ["Enable TranslationConfiguration option loadIncludes"]
    )
    private var loadIncludes: Boolean = false

    @CommandLine.Option(names = ["--includes-file"], description = ["Load includes from file"])
    private var includesFile: File? = null

    /**
     * Pushes the whole translationResult to the neo4j db.
     *
     * @param translationResult, not null
     * @throws InterruptedException, if the thread is interrupted while it try´s to connect to the
     * neo4j db.
     * @throws ConnectException, if there is no connection to bolt://localhost:7687 possible
     */
    @Throws(InterruptedException::class, ConnectException::class)
    fun pushToNeo4j(translationResult: TranslationResult) {
        Objects.requireNonNull(translationResult)
        val sessions = connect()
        val session = sessions.first
        if (PURGE_DB) session.purgeDatabase()
        pushNodes(translationResult, session)
        close(session, sessions.second)
    }

    /**
     * Connects to the neo4j db.
     *
     * @return a Pair of Optionals of the Session and the SessionFactory, if it is possible to connect
     * to neo4j. If it is not possible, the return value is a Pair of empty Optionals.
     * @throws InterruptedException, if the thread is interrupted while it try´s to connect to the
     * neo4j db.
     * @throws ConnectException, if there is no connection to bolt://localhost:7687 possible
     */
    @Throws(InterruptedException::class, ConnectException::class)
    private fun connect(): Pair<Session, SessionFactory> {
        var fails = 0
        var sessionFactory: SessionFactory? = null
        var session: Session? = null
        while (session == null && fails < MAX_COUNT_OF_FAILS) {
            try {
                val configuration = Configuration.Builder()
                        .uri(URI)
                        .autoIndex(AUTO_INDEX)
                        .credentials(neo4jUsername, neo4jPassword)
                        .verifyConnection(VERIFY_CONNECTION)
                        .build()
                sessionFactory = SessionFactory(configuration, "de.fraunhofer.aisec.cpg.graph")
                session = sessionFactory.openSession()
            } catch (ex: ConnectionException) {
                sessionFactory = null
                fails++
                log.error(
                        "Unable to connect to localhost:7687, "
                                + "ensure the database is running and that "
                                + "there is a working network connection to it.")
                Thread.sleep(TIME_BETWEEN_CONNECTION_TRIES.toLong())
            } catch (ex: AuthenticationException) {
                log.error("Unable to connect to localhost:7687, wrong username/password!")
                exitProcess(1)
            }
        }
        assert(fails <= MAX_COUNT_OF_FAILS)
        if (session == null || sessionFactory == null) {
            throw ConnectException("Unable to connect to localhost:7687")
        }
        return Pair(session, sessionFactory)
    }

    /**
     * PushNodes to the neo4j db.
     *
     * @param translationResult, not null,
     * @param session, not null,
     */
    private fun pushNodes(translationResult: TranslationResult, session: Session) {
        val translationUnitDeclarations = translationResult.translationUnits
        // Only to remove duplicated elements in the translationUnitDeclarations
        // This "Bug" will be solved in future releases of the cpg
        val nodes: Set<Node> = HashSet<Node>(translationUnitDeclarations)

        log.info("Using import depth: $depth")
        log.info("Count Translation units: " + nodes.size)
        val nodesToPush = nodes.stream().collect(
            {HashSet<Node>()},
            {set, elem -> set.addAll(SubgraphWalker.flattenAST(elem))},
            {set1, set2 -> set1.addAll(set2)}
        )
        log.info("Count nodes to save: " + nodesToPush.size)

        val transaction = session.beginTransaction()

        startTime = System.currentTimeMillis()

        session.save(nodesToPush, depth)

        val purePushTime = System.currentTimeMillis()

        log.info("Benchmark: pure push time: " + (purePushTime - startTime)/S_TO_MS_FACTOR + " s.")

        transaction.commit()
        transaction.close()
        session.clear()
    }

    /**
     * Clears the session and closes the sessionFactory
     *
     * @param session, not null
     * @param sessionFactory, not null
     */
    private fun close(session: Session, sessionFactory: SessionFactory) {
        session.clear()
        sessionFactory.close()
    }

    /**
     * Parse the file paths to analyze and set up the translationConfiguration with these paths.
     *
     * @throws IllegalArgumentException, if there was no arguments provided, or the path does not
     * point to a file, is a directory or point to a hidden file or the paths does not have the
     * same top level path.
     */
    private fun setupTranslationConfiguration(): TranslationConfiguration {
        assert(files.isNotEmpty())
        val filePaths = arrayOfNulls<File>(files.size)
        var topLevel: File? = null

        for (index in files.indices) {
            val path = Paths.get(files[index]).toAbsolutePath().normalize()
            val file = File(path.toString())
            require(file.exists() && (!file.isHidden)) { "Please use a correct path. It was: $path" }
            val currentTopLevel = if (file.isDirectory) file else file.parentFile
            if (topLevel == null)
                topLevel = currentTopLevel
            require(topLevel.toString() == currentTopLevel.toString()) { "All files should have the same top level path." }
            filePaths[index] = file
        }

        val translationConfiguration = TranslationConfiguration.builder()
            .sourceLocations(*filePaths)
            .topLevel(topLevel!!)
            .defaultPasses()
            .loadIncludes(loadIncludes)
            .debugParser(DEBUG_PARSER)

        includesFile?.let { theFile ->
            log.info("Load includes form file: $theFile")
            val baseDir = File(theFile.toString()).parentFile?.toString() ?: ""
            theFile.inputStream()
                .bufferedReader()
                .lines()
                .map(String::trim)
                .map {
                    if (Paths.get(it).isAbsolute) it
                    else Paths.get(baseDir, it).toString()
                }
                .forEach { translationConfiguration.includePath(it) }
        }

        return translationConfiguration.build()
    }

    /**
     * A generic pair.
     *
     * @author Andreas Hager, andreas.hager@aisec.fraunhofer.de
     */
    private class Pair<T, U>(val first: T, val second: U)

    /**
     * The entrypoint of the cpg-vis-neo4j.
     *
     * @throws IllegalArgumentException, if there was no arguments provided, or the path does not
     * point to a file, is a directory or point to a hidden file or the paths does not have the
     * same top level path
     * @throws InterruptedException, if the thread is interrupted while it try´s to connect to the
     * neo4j db.
     * @throws ConnectException, if there is no connection to bolt://localhost:7687 possible
     */
    @Throws(Exception::class, ConnectException::class, IllegalArgumentException::class)
    override fun call(): Int {
        val translationConfiguration = setupTranslationConfiguration()

        val startTime = System.currentTimeMillis()

        val translationManager = TranslationManager.builder().config(translationConfiguration).build()
        val translationResult = translationManager.analyze().get()

        val analyzingTime = System.currentTimeMillis()

        log.info("Benchmark: analyzing code in " + (analyzingTime - startTime)/S_TO_MS_FACTOR + " s.")

        pushToNeo4j(translationResult)

        val pushTime = System.currentTimeMillis()

        log.info("Benchmark: push code in " + (pushTime - analyzingTime)/S_TO_MS_FACTOR + " s.")

        return 0
    }


}


/**
 * Starts a command line application of the cpg-vis-neo4j.
 *
 * @throws IllegalArgumentException, if there was no arguments provided, or the path does not
 * point to a file, is a directory or point to a hidden file or the paths does not have the
 * same top level path
 * @throws InterruptedException, if the thread is interrupted while it try´s to connect to the
 * neo4j db.
 * @throws ConnectException, if there is no connection to bolt://localhost:7687 possible
 */
fun main(args: Array<String>) {
    val exitCode = CommandLine(Application()).execute(*args)
    exitProcess(exitCode)
}