import com.example.engine.*
fun main() {
    val parser = CommandParser()
    val plan = parser.parse("open github")
    println("ACTION: ${plan.actions.first().action}")
    println("REQUIRES_APPROVAL: ${plan.actions.first().requiresApproval}")
}
