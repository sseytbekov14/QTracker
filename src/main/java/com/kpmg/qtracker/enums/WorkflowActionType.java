package com.kpmg.qtracker.enums;

public enum WorkflowActionType {
    INITIATE,              // Отправка на апрув
    SUBMIT_TO_OPERATOR,    // Facilitator → Control Operator
    SUBMIT_TO_SOQM_LEAD,   // Control Operator → SoQM Lead
    RETURN_TO_FACILITATOR, // Control Operator → Facilitator (возврат)
    SUBMIT_TO_PROCESS_OWNER, // SoQM Lead → Process Owner
    RETURN_TO_OPERATOR,    // SoQM Lead → Control Operator (возврат)
    APPROVE,               // Утверждение
    RETURN,                // Возврат на доработку
    REJECT,                // Отклонение
    COMMENT,               // Добавление комментария
    REASSIGN               // Переназначение
}