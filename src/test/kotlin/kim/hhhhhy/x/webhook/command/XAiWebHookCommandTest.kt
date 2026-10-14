@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package kim.hhhhhy.x.webhook.command

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import net.mamoe.mirai.console.command.Command
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class XAiWebHookCommandTest {
    /** 只加载真实命令类的声明，不初始化插件、权限服务或启动 HTTP/浏览器。 */
    private fun declarations(): List<KFunction<*>> = Class.forName(
        "kim.hhhhhy.x.webhook.command.XAiWebHookCommand", false, javaClass.classLoader
    ).kotlin.functions.filter { it.findAnnotation<CompositeCommand.SubCommand>() !== null }

    /** 使用项目实际依赖的 mirai-console 反射器及其 CompositeCommand 注解解析器。 */
    private fun reflector(): Any {
        val command = Proxy.newProxyInstance(Command::class.java.classLoader, arrayOf(Command::class.java)) {
                proxy, method, arguments ->
            when (method.name) {
                "getPrimaryName" -> "xwebhook"
                "getSecondaryNames" -> emptyArray<String>()
                "getDescription", "getUsage", "toString" -> "command declaration regression"
                "getOverloads" -> emptyList<Any>()
                "getPrefixOptional" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("unexpected command getter during declaration validation: ${method.name}")
            }
        }
        val resolverType = Class.forName("net.mamoe.mirai.console.internal.command.SubCommandAnnotationResolver")
        val resolver = Class.forName("net.mamoe.mirai.console.internal.command.CompositeCommandSubCommandAnnotationResolver")
            .getField("INSTANCE").get(null)
        return Class.forName("net.mamoe.mirai.console.internal.command.CommandReflector")
            .getConstructor(Command::class.java, resolverType).newInstance(command, resolver)
    }

    private fun validate(reflector: Any, check: String, declaration: KFunction<*>) {
        val method = reflector.javaClass.getDeclaredMethod(check, KFunction::class.java)
        method.isAccessible = true
        method.invoke(reflector, declaration)
    }

    @Test
    fun `all production subcommands should pass the actual mirai registration declaration checks`() {
        val functions = declarations()
        assertEquals(7, functions.size)
        val names = functions.flatMap { assertNotNull(it.findAnnotation<CompositeCommand.SubCommand>()).value.toList() }
        assertTrue(names.containsAll(listOf("imageconfig", "imageconfigset", "imageconfigreload")))
        val reflector = reflector()
        // findSubCommands 在注册时实际调用的三项校验，直接检查生产类的 KFunction，避免只测字符串。
        for (function in functions) {
            for (check in listOf("checkExtensionReceiver", "checkModifiers", "checkNames")) {
                validate(reflector, check, function)
            }
        }
    }

    @Test
    fun `legacy hyphenated names should reproduce the mirai declaration failure`() {
        val reflector = reflector()
        val declarations = LegacyDeclarations::class.functions
            .filter { it.findAnnotation<CompositeCommand.SubCommand>() !== null }
        assertEquals(3, declarations.size)
        for (function in declarations) {
            val error = assertFailsWith<InvocationTargetException> { validate(reflector, "checkNames", function) }
            val cause = assertNotNull(error.cause)
            assertEquals("net.mamoe.mirai.console.internal.command.IllegalCommandDeclarationException", cause.javaClass.name)
            assertTrue(cause.message.orEmpty().contains("'-' is forbidden in command name."), cause.message)
        }
    }

    private class LegacyDeclarations {
        @CompositeCommand.SubCommand("image-config")
        fun CommandSender.show(): Unit = Unit

        @CompositeCommand.SubCommand("image-config-set")
        fun CommandSender.set(key: String, value: String): Unit = Unit

        @CompositeCommand.SubCommand("image-config-reload")
        fun CommandSender.reload(): Unit = Unit
    }
}
