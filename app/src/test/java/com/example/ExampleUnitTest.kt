package com.example

import com.example.data.local.CapabilityTag
import com.example.data.provider.ModelCapabilityRegistry
import com.example.data.security.CleartextHostGuardInterceptor
import com.example.data.security.CleartextHostNotApprovedException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun modelCapabilityRegistry_detectsOmniRouteCombosAndVision() {
        val combo = ModelCapabilityRegistry.buildDescriptor(
            providerInstanceId = "omni_1",
            rawModelId = "omniroute/auto-combo"
        )
        assertTrue(combo.isCombo)
        assertTrue(combo.supportsVision)
        assertTrue(CapabilityTag.CHAT in combo.capabilitySet())

        val o1Model = ModelCapabilityRegistry.buildDescriptor(
            providerInstanceId = "oai_1",
            rawModelId = "o1-mini"
        )
        assertFalse(o1Model.supportsTemperature)
        assertFalse(o1Model.supportsSystemPrompt)
    }

    @Test(expected = CleartextHostNotApprovedException::class)
    fun cleartextInterceptor_blocksUnapprovedHttpHost() {
        val approved = setOf("192.168.1.100")
        val client = OkHttpClient.Builder()
            .addInterceptor(CleartextHostGuardInterceptor { approved })
            .build()

        val req = Request.Builder()
            .url("http://192.168.1.250:20128/v1/models")
            .build()

        client.newCall(req).execute()
    }

    @Test
    fun mcpApprovalMode_distinguishesSafetyLevels() {
        val strict = com.example.data.local.McpApprovalMode.ASK_EVERY_TIME
        val session = com.example.data.local.McpApprovalMode.ASK_ONCE_PER_CONVERSATION
        val balanced = com.example.data.local.McpApprovalMode.ALWAYS_ALLOW_READ_ONLY

        org.junit.Assert.assertEquals("Ask Every Time", strict.label)
        org.junit.Assert.assertEquals("Ask Once / Thread", session.label)
        org.junit.Assert.assertEquals("Auto Read-Only", balanced.label)
    }

    @Test
    fun skillMarkdownParser_strictlyDetectsSensitiveMutatingTools() {
        // Sensitive write/delete/execute tools must return false (NEVER auto-run)
        assertFalse(com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool("delete_database", "Deletes production DB"))
        assertFalse(com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool("write_file", "Writes configuration to disk"))
        assertFalse(com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool("bash_exec", "Executes arbitrary terminal shell commands"))
        assertFalse(com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool("deploy_application", "Deploys service to cloud"))
        assertFalse(com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool("drop_table", "Drops SQL table"))

        // Safe query tools return true
        assertTrue(com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool("get_weather", "Queries current temperature"))
        assertTrue(com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool("read_document", "Reads markdown file content"))
        assertTrue(com.example.data.mcp.SkillMarkdownParser.isReadOnlyTool("search_issues", "Searches GitHub issues"))
    }
}
