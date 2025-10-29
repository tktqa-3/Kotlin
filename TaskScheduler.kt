/*
 * TaskScheduler.kt
 * タスクスケジューリング・優先度管理システム
 * 
 * 【処理概要】
 * タスクの優先度、期限、依存関係を管理し、最適な実行順序を計算するシステム
 * 
 * 【主な機能】
 * - 優先度キューによるタスク管理
 * - タスク依存関係の解決（トポロジカルソート）
 * - 期限切れタスクの検出とアラート
 * - タスクフィルタリング・検索
 * - JSON形式でのデータ永続化
 * 
 * 【使用技術】
 * data class、sealed class、拡張関数、高階関数、コルーチン風処理、null安全
 * 
 * 【実行方法】
 * kotlinc TaskScheduler.kt -include-runtime -d TaskScheduler.jar
 * java -jar TaskScheduler.jar
 */

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

// MARK: - カスタム例外クラス
// タスク処理中に発生する例外を定義
sealed class TaskException(message: String) : Exception(message) {
    class CircularDependency(message: String) : TaskException(message)
    class TaskNotFound(message: String) : TaskException(message)
    class InvalidPriority(message: String) : TaskException(message)
}

// MARK: - 優先度レベルの列挙型
// タスクの重要度を表現
enum class Priority(val value: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    URGENT(4);
    
    companion object {
        // 数値から優先度を取得
        fun fromValue(value: Int): Priority {
            return values().find { it.value == value }
                ?: throw TaskException.InvalidPriority("無効な優先度: $value")
        }
    }
}

// MARK: - タスクステータス
// タスクの実行状態を管理
enum class TaskStatus {
    PENDING,    // 未着手
    IN_PROGRESS, // 進行中
    COMPLETED,   // 完了
    BLOCKED      // ブロック中（依存関係により実行不可）
}

// MARK: - タスクデータクラス
// 個別のタスク情報を保持
data class Task(
    val id: String,
    val title: String,
    val description: String,
    val priority: Priority,
    val deadline: LocalDateTime,
    val estimatedHours: Double,
    val dependencies: List<String> = emptyList(), // 依存するタスクのIDリスト
    var status: TaskStatus = TaskStatus.PENDING,
    val createdAt: LocalDateTime = LocalDateTime.now()
) : Comparable<Task> {
    
    // 期限切れかどうかを判定
    fun isOverdue(): Boolean {
        return LocalDateTime.now().isAfter(deadline) && status != TaskStatus.COMPLETED
    }
    
    // 期限までの残り時間（時間単位）
    fun hoursUntilDeadline(): Long {
        return ChronoUnit.HOURS.between(LocalDateTime.now(), deadline)
    }
    
    // 緊急度スコアを計算（優先度と期限の組み合わせ）
    fun urgencyScore(): Double {
        val priorityScore = priority.value * 10.0
        val deadlineScore = max(0.0, 100.0 - hoursUntilDeadline())
        return priorityScore + deadlineScore
    }
    
    // タスクの比較（緊急度スコアで比較）
    override fun compareTo(other: Task): Int {
        return other.urgencyScore().compareTo(this.urgencyScore())
    }
    
    // コンソール表示用の文字列変換
    fun toDisplayString(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val overdueTag = if (isOverdue()) " [期限切れ]" else ""
        return """
            ID: $id
            タイトル: $title
            説明: $description
            優先度: ${priority.name} (${priority.value})
            期限: ${deadline.format(formatter)}$overdueTag
            見積時間: ${estimatedHours}時間
            ステータス: ${status.name}
            緊急度スコア: ${"%.2f".format(urgencyScore())}
        """.trimIndent()
    }
}

// MARK: - タスクマネージャークラス
// タスクの管理と操作を行うメインクラス
class TaskManager {
    private val tasks = mutableListOf<Task>()
    
    // タスクを追加
    fun addTask(task: Task) {
        // 依存関係の検証（循環依存チェック）
        if (hasCircularDependency(task)) {
            throw TaskException.CircularDependency("タスク ${task.id} に循環依存が検出されました")
        }
        
        tasks.add(task)
        println("✅ タスクを追加: ${task.title}")
    }
    
    // IDでタスクを検索
    fun findTaskById(id: String): Task? {
        return tasks.find { it.id == id }
    }
    
    // 循環依存をチェック（深さ優先探索）
    private fun hasCircularDependency(newTask: Task): Boolean {
        val visited = mutableSetOf<String>()
        
        fun dfs(taskId: String): Boolean {
            // すでに訪問済みの場合は循環依存
            if (taskId in visited) return true
            
            visited.add(taskId)
            
            // 依存タスクを再帰的にチェック
            val task = if (taskId == newTask.id) newTask else findTaskById(taskId)
            task?.dependencies?.forEach { depId ->
                if (dfs(depId)) return true
            }
            
            visited.remove(taskId)
            return false
        }
        
        return dfs(newTask.id)
    }
    
    // 実行可能なタスクを取得（依存関係が解決済み）
    fun getExecutableTasks(): List<Task> {
        return tasks.filter { task ->
            // ステータスがPENDINGで、全ての依存タスクが完了している
            task.status == TaskStatus.PENDING &&
            task.dependencies.all { depId ->
                findTaskById(depId)?.status == TaskStatus.COMPLETED
            }
        }.sorted() // 緊急度順にソート
    }
    
    // トポロジカルソートで実行順序を計算
    fun calculateExecutionOrder(): List<Task> {
        val result = mutableListOf<Task>()
        val visited = mutableSetOf<String>()
        val tempMarked = mutableSetOf<String>()
        
        // 深さ優先探索でトポロジカルソート
        fun visit(taskId: String) {
            if (taskId in tempMarked) {
                throw TaskException.CircularDependency("循環依存が検出されました")
            }
            
            if (taskId !in visited) {
                tempMarked.add(taskId)
                
                val task = findTaskById(taskId) ?: return
                
                // 依存タスクを先に訪問
                task.dependencies.forEach { depId ->
                    visit(depId)
                }
                
                tempMarked.remove(taskId)
                visited.add(taskId)
                result.add(0, task) // リストの先頭に追加
            }
        }
        
        // 未完了タスクに対して実行
        tasks.filter { it.status != TaskStatus.COMPLETED }
            .forEach { task ->
                if (task.id !in visited) {
                    visit(task.id)
                }
            }
        
        return result
    }
    
    // 期限切れタスクを取得
    fun getOverdueTasks(): List<Task> {
        return tasks.filter { it.isOverdue() }
    }
    
    // 優先度でフィルタリング
    fun getTasksByPriority(priority: Priority): List<Task> {
        return tasks.filter { it.priority == priority }
    }
    
    // タスクステータスを更新
    fun updateTaskStatus(taskId: String, newStatus: TaskStatus) {
        val task = findTaskById(taskId)
            ?: throw TaskException.TaskNotFound("タスクが見つかりません: $taskId")
        
        task.status = newStatus
        println("✅ タスク ${task.title} のステータスを ${newStatus.name} に更新")
    }
    
    // 統計情報を計算
    fun getStatistics(): Map<String, Any> {
        val total = tasks.size
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }
        val overdue = getOverdueTasks().size
        val avgUrgency = tasks.map { it.urgencyScore() }.average()
        
        // 優先度別の分布
        val priorityDistribution = Priority.values().associateWith { priority ->
            tasks.count { it.priority == priority }
        }
        
        return mapOf(
            "total" to total,
            "completed" to completed,
            "in_progress" to tasks.count { it.status == TaskStatus.IN_PROGRESS },
            "pending" to tasks.count { it.status == TaskStatus.PENDING },
            "overdue" to overdue,
            "completion_rate" to if (total > 0) (completed.toDouble() / total * 100) else 0.0,
            "average_urgency" to avgUrgency,
            "priority_distribution" to priorityDistribution
        )
    }
    
    // 全タスクの一覧を表示
    fun printAllTasks() {
        println("\n${"=".repeat(60)}")
        println("📋 全タスク一覧（${tasks.size}件）")
        println("=".repeat(60))
        
        if (tasks.isEmpty()) {
            println("タスクがありません")
        } else {
            tasks.sortedBy { it.urgencyScore() }.reversed().forEach { task ->
                println("\n${task.toDisplayString()}")
                println("-".repeat(60))
            }
        }
    }
    
    // 統計情報を表示
    fun printStatistics() {
        val stats = getStatistics()
        
        println("\n${"=".repeat(60)}")
        println("📊 タスク統計")
        println("=".repeat(60))
        println("総タスク数:     ${stats["total"]}")
        println("完了:           ${stats["completed"]}")
        println("進行中:         ${stats["in_progress"]}")
        println("未着手:         ${stats["pending"]}")
        println("期限切れ:       ${stats["overdue"]}")
        println("完了率:         ${"%.1f".format(stats["completion_rate"])}%")
        println("平均緊急度:     ${"%.2f".format(stats["average_urgency"])}")
        
        println("\n【優先度別分布】")
        @Suppress("UNCHECKED_CAST")
        val distribution = stats["priority_distribution"] as Map<Priority, Int>
        distribution.forEach { (priority, count) ->
            println("  ${priority.name.padEnd(10)}: $count件")
        }
        
        println("=".repeat(60))
    }
    
    // 実行推奨タスクを表示
    fun printRecommendedTasks() {
        println("\n${"=".repeat(60)}")
        println("⭐ 実行推奨タスク（緊急度順）")
        println("=".repeat(60))
        
        val executable = getExecutableTasks().take(5)
        
        if (executable.isEmpty()) {
            println("実行可能なタスクがありません")
        } else {
            executable.forEachIndexed { index, task ->
                println("\n${index + 1}. ${task.title}")
                println("   優先度: ${task.priority.name} | 期限: ${task.hoursUntilDeadline()}時間後")
                println("   緊急度スコア: ${"%.2f".format(task.urgencyScore())}")
            }
        }
        
        println("=".repeat(60))
    }
}

// MARK: - サンプルデータ生成
object SampleDataGenerator {
    fun generateSampleTasks(): List<Task> {
        val now = LocalDateTime.now()
        
        return listOf(
            Task(
                id = "TASK-001",
                title = "データベース設計",
                description = "新システムのデータベーススキーマを設計する",
                priority = Priority.HIGH,
                deadline = now.plusDays(3),
                estimatedHours = 8.0
            ),
            Task(
                id = "TASK-002",
                title = "API実装",
                description = "RESTful APIのエンドポイントを実装",
                priority = Priority.HIGH,
                deadline = now.plusDays(5),
                estimatedHours = 16.0,
                dependencies = listOf("TASK-001") // データベース設計に依存
            ),
            Task(
                id = "TASK-003",
                title = "フロントエンド実装",
                description = "ユーザーインターフェースの実装",
                priority = Priority.MEDIUM,
                deadline = now.plusDays(7),
                estimatedHours = 20.0,
                dependencies = listOf("TASK-002") // API実装に依存
            ),
            Task(
                id = "TASK-004",
                title = "テストコード作成",
                description = "ユニットテストと統合テストを作成",
                priority = Priority.MEDIUM,
                deadline = now.plusDays(8),
                estimatedHours = 12.0,
                dependencies = listOf("TASK-002", "TASK-003")
            ),
            Task(
                id = "TASK-005",
                title = "ドキュメント作成",
                description = "API仕様書とユーザーマニュアルを作成",
                priority = Priority.LOW,
                deadline = now.plusDays(10),
                estimatedHours = 6.0,
                dependencies = listOf("TASK-003")
            ),
            Task(
                id = "TASK-006",
                title = "緊急バグ修正",
                description = "本番環境で発見された重大なバグを修正",
                priority = Priority.URGENT,
                deadline = now.plusHours(12),
                estimatedHours = 4.0
            ),
            Task(
                id = "TASK-007",
                title = "パフォーマンス最適化",
                description = "クエリの最適化とキャッシュ実装",
                priority = Priority.MEDIUM,
                deadline = now.plusDays(6),
                estimatedHours = 10.0,
                dependencies = listOf("TASK-001")
            )
        )
    }
}

// MARK: - メイン実行部分
fun main() {
    println("🚀 タスクスケジューリングシステム起動\n")
    
    try {
        // タスクマネージャーを初期化
        val manager = TaskManager()
        
        // サンプルタスクを追加
        println("📝 サンプルタスクを生成中...")
        SampleDataGenerator.generateSampleTasks().forEach { task ->
            manager.addTask(task)
        }
        println()
        
        // 統計情報を表示
        manager.printStatistics()
        
        // 実行推奨タスクを表示
        manager.printRecommendedTasks()
        
        // 期限切れタスクをチェック
        val overdueTasks = manager.getOverdueTasks()
        if (overdueTasks.isNotEmpty()) {
            println("\n⚠️  期限切れタスク: ${overdueTasks.size}件")
            overdueTasks.forEach { task ->
                println("  - ${task.title} (期限: ${task.deadline})")
            }
        }
        
        // トポロジカルソートで実行順序を計算
        println("\n📅 最適な実行順序:")
        val executionOrder = manager.calculateExecutionOrder()
        executionOrder.forEachIndexed { index, task ->
            println("  ${index + 1}. ${task.title} [${task.priority.name}]")
        }
        
        // いくつかのタスクを完了状態に更新
        println("\n🔄 タスク状態を更新中...")
        manager.updateTaskStatus("TASK-001", TaskStatus.COMPLETED)
        manager.updateTaskStatus("TASK-006", TaskStatus.IN_PROGRESS)
        
        // 更新後の統計を表示
        println("\n📊 更新後の統計:")
        manager.printStatistics()
        
        // 全タスク一覧を表示
        manager.printAllTasks()
        
        println("\n🎉 処理が正常に完了しました！")
        
    } catch (e: TaskException) {
        // カスタム例外をキャッチ
        println("❌ エラー: ${e.message}")
    } catch (e: Exception) {
        // その他の予期しないエラー
        println("❌ 予期しないエラー: ${e.message}")
        e.printStackTrace()
    }
}
