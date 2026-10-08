package io.github.hugfeature.hydrationbreak

/** Offline, small-range adaptations of NHS sitting exercises; see README for sources. */
object OfficeExercises {
    data class Move(val title: String, val steps: String)
    const val EXTRA_MOVE = "office_move"
    const val CAUTION = "使用稳固、无轮子的椅子，留出舒适空间。动作轻缓、不憋气，不追求幅度；疼痛、头晕或不适就停止。有伤病或不确定是否适合时，先咨询专业人员。"
    val moves = listOf(
        Move("坐姿小幅抬膝", "坐直，扶住椅子两侧。保持膝盖弯曲，缓慢抬起一只脚少许，再轻轻放下；左右交替，各做 5 次。"),
        Move("坐姿轻转肩背", "坐直，双脚平放，双臂交叉放在胸前。髋部不动，上身向左轻转至舒适位置，停约 5 秒后回正，再换右侧；各做 3 次。"),
        Move("缓慢转头", "坐直、肩膀放松。缓慢向左转头至舒适位置，停约 5 秒后回正，再换右侧；各做 3 次，不绕颈、不用力扳头。")
    )
    fun at(index: Int) = moves[Math.floorMod(index, moves.size)]
}
