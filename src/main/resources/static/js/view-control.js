console.log('🔥 VIEW-CONTROL.JS v2.3 ЗАГРУЖЕН - ОЧИЩЕН ОТ НЕИСПОЛЬЗУЕМОГО КОДА');

let changelogEntries = [];
let changelogFilter = 'all';
let changelogFilterInitialized = false;
let detailsDataCache = null;

const viewControl = (function() {
    let allUsers = [];
    let facilitatorUsers = [];
    let controlOperatorUsers = [];
    let soqmLeadUsers = [];
    let processOwnerUsers = [];
    let sharedWithUsers = [];
    let fullEditEnabled = false;
    let canEditStepsPerformed = false;
    let canEditProcessOwnerComments = false;
    let stepsPerformedEditOnly = false;
    let processOwnerCommentsEditOnly = false;

    let isDropdownOpen = false;
    let isControlOperatorDropdownOpen = false;
    let isSoqmLeadDropdownOpen = false;
    let isProcessOwnerDropdownOpen = false;
    let isSharedWithDropdownOpen = false;
    let currentWorkflowButton = null;
    let currentControlId = null;
    let selectedUser = null;
    let selectedControlOperator = null;
    let selectedSoqmLead = null;
    let selectedProcessOwner = null;
    let selectedSharedWithUsers = []; // Array instead of single user
    let editModeSnapshot = null;

    function isSoqmLeadRole() {
        const role = document.getElementById('currentUserRole')?.value || '';
        return role === 'SOQM_LEAD';
    }

    async function loadPermissions(controlId) {
        if (!controlId) {
            fullEditEnabled = isSoqmLeadRole();
            canEditStepsPerformed = false;
            canEditProcessOwnerComments = false;
            stepsPerformedEditOnly = false;
            processOwnerCommentsEditOnly = false;
            window.qtrackerPermissions = {
                canEditStepsPerformed: canEditStepsPerformed,
                canEditProcessOwnerComments: canEditProcessOwnerComments,
                canEditAll: fullEditEnabled
            };
            return;
        }
        try {
            const response = await fetch('/api/permissions/' + controlId);
            if (!response.ok) {
                throw new Error(`Failed to load permissions (${response.status})`);
            }
            const data = await response.json();
            const permissions = data && data.permissions ? data.permissions : {};
            fullEditEnabled = Boolean(permissions.canEditAll);
            canEditStepsPerformed = Boolean(permissions.canEditStepsPerformed);
            canEditProcessOwnerComments = Boolean(permissions.canEditProcessOwnerComments);
            stepsPerformedEditOnly = canEditStepsPerformed && !fullEditEnabled;
            processOwnerCommentsEditOnly = canEditProcessOwnerComments
                && !fullEditEnabled
                && !stepsPerformedEditOnly;
            window.qtrackerPermissions = {
                canEditStepsPerformed: canEditStepsPerformed,
                canEditProcessOwnerComments: canEditProcessOwnerComments,
                canEditAll: fullEditEnabled
            };
        } catch (error) {
            console.warn('Permissions fetch failed, falling back to role check:', error);
            fullEditEnabled = isSoqmLeadRole();
            const roleValue = document.getElementById('currentUserRole')?.value || '';
            canEditStepsPerformed = roleValue === 'FACILITATOR' || roleValue === 'CONTROL_OPERATOR';
            canEditProcessOwnerComments = (document.getElementById('currentUserRole')?.value || '') === 'PROCESS_OWNER';
            stepsPerformedEditOnly = canEditStepsPerformed && !fullEditEnabled;
            processOwnerCommentsEditOnly = canEditProcessOwnerComments
                && !fullEditEnabled
                && !stepsPerformedEditOnly;
            window.qtrackerPermissions = {
                canEditStepsPerformed: canEditStepsPerformed,
                canEditProcessOwnerComments: canEditProcessOwnerComments,
                canEditAll: fullEditEnabled
            };
        }
    }

    function getFieldSnapshotKey(field, index) {
        if (field.id) {
            return `id:${field.id}`;
        }
        const formId = field.form && field.form.id ? field.form.id : 'no-form';
        const name = field.name || field.tagName.toLowerCase();
        return `${formId}:${name}:${index}`;
    }

    function findUserByEmail(email) {
        if (!email) {
            return null;
        }
        return allUsers.find(user => user && user.mail === email) || null;
    }

    function captureEditModeSnapshot() {
        const fields = document.querySelectorAll('#controlForm input, #controlForm textarea, #controlForm select, #detailsForm input, #detailsForm textarea, #detailsForm select, #assignmentForm input, #assignmentForm textarea, #assignmentForm select, #documentsForm input, #documentsForm textarea, #documentsForm select');
        const fieldValues = {};

        fields.forEach((field, index) => {
            const key = getFieldSnapshotKey(field, index);
            if (field.type === 'checkbox' || field.type === 'radio') {
                fieldValues[key] = { checked: field.checked };
            } else if (field.type === 'file') {
                fieldValues[key] = { value: '' };
            } else {
                fieldValues[key] = { value: field.value || '' };
            }
        });

        editModeSnapshot = {
            fieldValues: fieldValues,
            facilitatorMail: document.getElementById('facilitatorHidden')?.value || '',
            controlOperatorMail: document.getElementById('controlOperatorHidden')?.value || '',
            soqmLeadMail: document.getElementById('soqmLeadHidden')?.value || '',
            processOwnerMail: document.getElementById('processOwnerHidden')?.value || '',
            sharedWithMails: selectedSharedWithUsers.map(user => user.mail).filter(Boolean)
        };
    }

    function restoreFromEditModeSnapshot() {
        if (!editModeSnapshot || !editModeSnapshot.fieldValues) {
            return;
        }

        const fields = document.querySelectorAll('#controlForm input, #controlForm textarea, #controlForm select, #detailsForm input, #detailsForm textarea, #detailsForm select, #assignmentForm input, #assignmentForm textarea, #assignmentForm select, #documentsForm input, #documentsForm textarea, #documentsForm select');

        fields.forEach((field, index) => {
            const key = getFieldSnapshotKey(field, index);
            const saved = editModeSnapshot.fieldValues[key];
            if (!saved) {
                return;
            }
            if (field.type === 'checkbox' || field.type === 'radio') {
                field.checked = Boolean(saved.checked);
            } else if (field.type !== 'file') {
                field.value = saved.value || '';
            } else {
                field.value = '';
            }
        });

        selectedUser = findUserByEmail(editModeSnapshot.facilitatorMail);
        selectedControlOperator = findUserByEmail(editModeSnapshot.controlOperatorMail);
        selectedSoqmLead = findUserByEmail(editModeSnapshot.soqmLeadMail);
        selectedProcessOwner = findUserByEmail(editModeSnapshot.processOwnerMail);
        selectedSharedWithUsers = (editModeSnapshot.sharedWithMails || [])
            .map(findUserByEmail)
            .filter(Boolean);

        const facilitatorInput = document.getElementById('facilitatorInput');
        if (facilitatorInput) facilitatorInput.value = selectedUser ? selectedUser.displayName : '';
        const facilitatorHidden = document.getElementById('facilitatorHidden');
        if (facilitatorHidden) facilitatorHidden.value = editModeSnapshot.facilitatorMail || '';

        const controlOperatorInput = document.getElementById('controlOperatorInput');
        if (controlOperatorInput) controlOperatorInput.value = selectedControlOperator ? selectedControlOperator.displayName : '';
        const controlOperatorHidden = document.getElementById('controlOperatorHidden');
        if (controlOperatorHidden) controlOperatorHidden.value = editModeSnapshot.controlOperatorMail || '';

        const soqmLeadInput = document.getElementById('soqmLeadInput');
        if (soqmLeadInput) soqmLeadInput.value = selectedSoqmLead ? selectedSoqmLead.displayName : '';
        const soqmLeadHidden = document.getElementById('soqmLeadHidden');
        if (soqmLeadHidden) soqmLeadHidden.value = editModeSnapshot.soqmLeadMail || '';

        const processOwnerInput = document.getElementById('processOwnerInput');
        if (processOwnerInput) processOwnerInput.value = selectedProcessOwner ? selectedProcessOwner.displayName : '';
        const processOwnerHidden = document.getElementById('processOwnerHidden');
        if (processOwnerHidden) processOwnerHidden.value = editModeSnapshot.processOwnerMail || '';

        updateSharedWithDisplay();
        updateSharedWithHidden();
        normalizeAssignmentDateFieldsForDisplay();
    }

    async function loadAllUsers() {
        try {
            const response = await fetch('/api/users/all');
            allUsers = await response.json();
            console.log('✅ Загружено пользователей:', allUsers.length);
            return allUsers;
        } catch (error) {
            console.error('❌ Error loading users:', error);
            return [];
        }
    }

    async function loadUsersByRole(role) {
        try {
            const response = await fetch(`/api/users/role/${role}`);
            const users = await response.json();
            console.log(`✅ Загружено пользователей с ролью ${role}:`, users.length);
            return users;
        } catch (error) {
            console.error(`❌ Error loading users with role ${role}:`, error);
            return [];
        }
    }

    // Workflow variables
    let currentWorkflowAction = null;
    let currentWorkflowRequiresComment = false;

// Global function for showing workflow buttons by status and role
window.showWorkflowButtonsByStatusAndRole = function(status, userRole) {
    console.log('🔘 ГЛОБАЛЬНАЯ ФУНКЦИЯ: ПОКАЗ КНОПОК');
    console.log('   Статус:', status);
    console.log('   Роль:', userRole);

    const container = document.getElementById('workflow-buttons-container');
    if (!container) {
        console.error('❌ Контейнер не найден');
        return;
    }

    const buttons = container.querySelectorAll('.workflow-btn');
    console.log(`   Найдено кнопок: ${buttons.length}`);

    // Показываем все кнопки для указанной роли
    buttons.forEach(btn => {
        if (btn.dataset.role === userRole) {
            btn.style.display = 'inline-block';
            console.log(`   ✅ Показываем: "${btn.textContent.trim()}"`);
        } else {
            btn.style.display = 'none';
        }
    });

    container.style.display = 'inline-flex';

    console.log('✅ Кнопки должны быть видны!');
};

// Создадим простую глобальную функцию
window.showAllWorkflowButtons = function() {
    console.log('=== ПОКАЗ ВСЕХ КНОПОК ===');

    // Получаем данные
    const userRole = document.getElementById('currentUserRole').value;
    const status = document.getElementById('currentPerformanceStatus').value;

    console.log('Ваша роль:', userRole);
    console.log('Статус:', status);

    // Находим все кнопки
    const buttons = document.querySelectorAll('.workflow-btn');
    console.log(`Найдено кнопок: ${buttons.length}`);

    // Показываем все кнопки для вашей роли
    let visibleCount = 0;
    buttons.forEach(btn => {
        if (btn.dataset.role === userRole) {
            btn.style.display = 'inline-block';
            visibleCount++;
            console.log(`✅ "${btn.textContent.trim()}" - ПОКАЗАНО`);
        } else {
            btn.style.display = 'none';
        }
    });

    // Показываем контейнер без рамки
    const container = document.getElementById('workflow-buttons-container');
    if (container) {
        container.style.display = 'inline-flex';
    }

    console.log(`🎉 Показано ${visibleCount} кнопок для ${userRole}`);
    console.log('=== КОНЕЦ ===');
};

    // Инициализация workflow кнопок (добавить в init метод)
function handleWorkflowButtonClick(event) {
    const button = event.currentTarget;
    const action = button.dataset.action;
    const label = button.textContent;
    const requiresComment = button.dataset.requiresComment === 'true';
    const needsApproval = button.dataset.needsApproval === 'true';
    const commentLabel = button.dataset.commentLabel || 'Comment';

    currentWorkflowButton = button;

    // Настроить модальное окно
    const modal = document.getElementById('workflowActionModal');
    const message = modal.querySelector('#workflowActionMessage');
    const approvalSection = modal.querySelector('#workflowApprovalSection');
    const commentSection = modal.querySelector('#workflowCommentSection');
    const commentLabelElement = modal.querySelector('#commentLabel');

    // Установить сообщение
    const confirmationText = getConfirmationText(action, label);
    message.textContent = confirmationText;

    // Показать/скрыть секцию Yes/No (только для SoQM Lead)
    const isSoqmAction = action === 'SEND_TO_PROCESS_OWNER' || action === 'SOQM_COMMENT';
    approvalSection.style.display = (needsApproval && isSoqmAction) ? 'block' : 'none';

    // Показать/скрыть секцию комментария
    commentSection.style.display = requiresComment ? 'block' : 'none';

    if (requiresComment) {
        commentLabelElement.textContent = commentLabel;
        modal.querySelector('#workflowComment').value = '';
    }

    // Сбросить радиокнопки
    if (needsApproval) {
        modal.querySelector('#soqmApprovalNo').checked = true;
    }

    // Показать модальное окно
    const bsModal = new bootstrap.Modal(modal);
    bsModal.show();
}

function getConfirmationText(action, label) {
    const messages = {
        'INITIATE': 'Initiate this control? Status will change from "Draft" to "In Progress".',
        'SUBMIT_FOR_REVIEW': 'Submit this control for review? Status will change to "Review" and Control Operator will be notified.',
        'SUBMIT_FOR_SOQM': 'Submit this control to SOQM Lead? Status will change to "SoQM Head Review".',
        'RETURN_TO_FACILITATOR': 'Return this control to Facilitator for revision?',
        'SEND_TO_PROCESS_OWNER': 'Send this control to Process Owner? Status will change to "Process Owner Review".',
        'SEND_BACK_TO_OPERATOR': 'Send this control back to Control Operator?',
        'COMPLETE': 'Complete this control workflow? Status will change to "Completed".',
        'SOQM_COMMENT': 'Add SOQM Head/Team comments'
    };

    return messages[action] || `Are you sure you want to: ${label}?`;
}

function confirmWorkflowAction() {
    const modal = document.getElementById('workflowActionModal');
    const commentInput = modal.querySelector('#workflowComment');
    const comment = commentInput ? commentInput.value.trim() : '';

    const needsApproval = currentWorkflowButton?.dataset.needsApproval === 'true';
    const requiresComment = currentWorkflowButton?.dataset.requiresComment === 'true';
    const action = currentWorkflowButton?.dataset.action;

    // Проверка для SoQM approval
    if (needsApproval && (action === 'SEND_TO_PROCESS_OWNER' || action === 'SOQM_COMMENT')) {
        const selectedApproval = modal.querySelector('input[name="soqmApproval"]:checked');
        if (!selectedApproval) {
            showErrorMessage('Please select Yes or No for SoQM approval');
            return;
        }

        // Добавляем информацию об approval в комментарий
        const approvalComment = selectedApproval.value === 'YES'
            ? '[SOQM APPROVED: YES] '
            : '[SOQM APPROVED: NO] ';

        const finalComment = approvalComment + (comment || '');

        // Закрыть модальное окно
        const bsModal = bootstrap.Modal.getInstance(modal);
        bsModal.hide();

        // Отправить действие на сервер
        performWorkflowAction(action, finalComment);
        return;
    }

    // Обычная валидация
    if (requiresComment && (!comment || comment === '')) {
        showErrorMessage('Please enter a comment');
        return;
    }

    // Закрыть модальное окно
    const bsModal = bootstrap.Modal.getInstance(modal);
    bsModal.hide();

    // Отправить действие на сервер
    performWorkflowAction(action, comment);
}

    // Показать модальное окно подтверждения
    function showWorkflowConfirmationModal(action, label, requiresComment, confirmationMessage) {
        // Если у тебя уже есть функция showModal или подобная, используй её
        // Иначе создай простую версию:

        const modalMessage = confirmationMessage || `Are you sure you want to perform: ${label}?`;

        if (requiresComment) {
            // Показать модальное окно с полем для комментария
            showCommentModal(modalMessage, action);
        } else {
            // Просто подтверждение без комментария
            if (confirm(modalMessage)) {
                performWorkflowAction(action, null);
            }
        }
    }

    // Функция показа модального окна с комментарием
    function showCommentModal(message, action) {
        // Если у тебя уже есть система модальных окон, интегрируй в неё
        // Иначе создай простое:

        const modalHTML = `
            <div class="modal fade" id="workflowCommentModal" tabindex="-1">
                <div class="modal-dialog">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title">Workflow Action</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                        </div>
                        <div class="modal-body">
                            <p>${message}</p>
                            <div class="mt-3">
                                <label for="workflowComment" class="form-label">Comment (required):</label>
                                <textarea id="workflowComment" class="form-control" rows="3"
                                          placeholder="Enter your comment here..."></textarea>
                            </div>
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
                            <button type="button" class="btn btn-primary" onclick="submitWorkflowAction()">Confirm</button>
                        </div>
                    </div>
                </div>
            </div>
        `;

        // Удалить старую модалку если есть
        const oldModal = document.getElementById('workflowCommentModal');
        if (oldModal) oldModal.remove();

        // Добавить новую
        document.body.insertAdjacentHTML('beforeend', modalHTML);

        // Показать
        const modal = new bootstrap.Modal(document.getElementById('workflowCommentModal'));
        modal.show();
    }

    function getCurrentControlId() {
        // Пример: из URL /view-control/123 возвращает 123
        const path = window.location.pathname;
        const match = path.match(/\/view-control\/(\d+)/);
        return match ? match[1] : null;
    }

    // Отправить workflow действие
    async function performWorkflowAction(action, comment) {
        const controlId = getCurrentControlId(); // Используй свою функцию

        try {
            const ready = await ensureWorkflowRoleReady();
            if (!ready) {
                return;
            }
            const response = await fetch('/api/workflow/perform-action', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    controlId: controlId,
                    action: action,
                    comment: comment
                })
            });

            if (response.ok) {
                showSuccessMessage('Action completed successfully!');

                // Redirect to dashboard after a short pause
                setTimeout(() => {
                    window.location.href = '/';
                }, 800);

            } else {
                const error = await response.text();
                showErrorMessage('Error: ' + error);
            }

        } catch (error) {
            console.error('Error performing workflow action:', error);
            showErrorMessage('Network error. Please try again.');
        }
    }

    function showSuccessMessage(message) {
        // Используй свою систему уведомлений
        alert(message); // или toast, или что-то другое
    }

    function showErrorMessage(message) {
        alert('Error: ' + message);
    }

    // Функция для отправки (вызывается из модального окна)
    function submitWorkflowAction() {
        const commentInput = document.getElementById('workflowComment');
        const comment = commentInput ? commentInput.value.trim() : '';

        if (currentWorkflowRequiresComment && (!comment || comment === '')) {
            showErrorMessage('Please enter a comment');
            return;
        }

        // Закрыть модальное окно
        const modal = bootstrap.Modal.getInstance(document.getElementById('workflowCommentModal'));
        if (modal) modal.hide();

        performWorkflowAction(currentWorkflowAction, comment);
    }

    // Скрыть workflow кнопки
    function hideWorkflowButtons() {
        const container = document.getElementById('workflow-buttons-container');
        if (container) {
            container.innerHTML = '';
            container.style.display = 'none';
        }
    }

    function createCompactUserItem(user, isSelected = false) {
        const listItem = document.createElement('div');
        listItem.className = `compact-user-item ${isSelected ? 'selected' : ''}`;
        listItem.dataset.email = user.mail;

        const initials = user.displayName
            .split(' ')
            .map(name => name.charAt(0))
            .join('')
            .toUpperCase()
            .substring(0, 2);

        listItem.innerHTML = `
            <div class="user-initials">${initials}</div>
            <div class="user-details">
                <div class="user-name">${user.displayName}</div>
                <div class="user-email">${user.mail}</div>
            </div>
        `;

        return listItem;
    }

    // ========== FACILITATOR FUNCTIONS ==========
    function toggleUserDropdown() {
        if (!isAssignmentDropdownEditable('facilitatorInput')) {
            return;
        }
        const dropdown = document.getElementById('userDropdown');
        if (!isDropdownOpen) {
            dropdown.style.display = 'block';
            isDropdownOpen = true;

            const searchInput = document.getElementById('facilitatorSearchInput');
            if (searchInput) searchInput.value = '';

            if (facilitatorUsers.length === 0) {
                // Facilitator and Control Operator are interchangeable
                Promise.all([
                    loadUsersByRole('FACILITATOR'),
                    loadUsersByRole('CONTROL_OPERATOR')
                ]).then(([facilitators, operators]) => {
                    const seen = new Set();
                    facilitatorUsers = [];
                    [...facilitators, ...operators].forEach(u => {
                        if (!seen.has(u.mail || u.email)) {
                            seen.add(u.mail || u.email);
                            facilitatorUsers.push(u);
                        }
                    });
                    displayUserDropdownList(facilitatorUsers);
                });
            } else {
                displayUserDropdownList(facilitatorUsers);
            }

            setTimeout(() => {
                const searchInput = document.getElementById('facilitatorSearchInput');
                if (searchInput) searchInput.focus();
            }, 100);
        } else {
            closeUserDropdown();
        }
    }

    function closeUserDropdown() {
        const dropdown = document.getElementById('userDropdown');
        dropdown.style.display = 'none';
        isDropdownOpen = false;
    }

    function displayUserDropdownList(users) {
        const usersList = document.getElementById('facilitatorUsersList');
        if (!usersList) return;

        usersList.innerHTML = '';

        if (users.length === 0) {
            usersList.innerHTML = '<div class="no-users-message">No users found</div>';
            return;
        }

        const sortedUsers = [...users].sort((a, b) => a.displayName.localeCompare(b.displayName));

        sortedUsers.forEach(user => {
            const isSelected = selectedUser && selectedUser.mail === user.mail;
            const listItem = createCompactUserItem(user, isSelected);
            listItem.addEventListener('click', function() {
                selectUser(user);
            });
            usersList.appendChild(listItem);
        });
    }

    function filterUserList() {
        const searchInput = document.getElementById('facilitatorSearchInput');
        if (!searchInput) return;

        const query = searchInput.value.trim().toLowerCase();
        console.log('🔍 Facilitator search query:', query);
        console.log('📋 Available facilitators:', facilitatorUsers);

        if (query === '') {
            displayUserDropdownList(facilitatorUsers);
            return;
        }

        const filteredUsers = facilitatorUsers.filter(user => {
            if (!user) return false;
            
            const displayNameMatch = user.displayName && user.displayName.toLowerCase().startsWith(query);
            const usernameMatch = user.username && user.username.toLowerCase().startsWith(query);
            const mailMatch = user.mail && user.mail.toLowerCase().startsWith(query);
            
            const matches = displayNameMatch || usernameMatch || mailMatch;
            
            if (matches) {
                console.log('✅ Match found:', user.displayName, user.username, user.mail);
            }
            
            return matches;
        });

        console.log('🎯 Filtered users count:', filteredUsers.length);
        displayUserDropdownList(filteredUsers);
    }

    function selectUser(user) {
        selectedUser = user;

        const input = document.getElementById('facilitatorInput');
        if (input) input.value = user.displayName;

        const hiddenInput = document.getElementById('facilitatorHidden');
        if (hiddenInput) hiddenInput.value = user.mail;

        displayUserDropdownList(facilitatorUsers);
        closeUserDropdown();
    }

    function isAssignmentDropdownEditable(inputId) {
        const input = document.getElementById(inputId);
        if (!input) {
            return true;
        }
        return !(input.disabled || input.readOnly || input.classList.contains('readonly-field'));
    }

    // ========== CONTROL OPERATOR FUNCTIONS ==========
    function toggleControlOperatorDropdown() {
        if (!isAssignmentDropdownEditable('controlOperatorInput')) {
            return;
        }
        const dropdown = document.getElementById('controlOperatorDropdown');
        if (!isControlOperatorDropdownOpen) {
            dropdown.style.display = 'block';
            isControlOperatorDropdownOpen = true;

            const searchInput = document.getElementById('controlOperatorSearchInput');
            if (searchInput) searchInput.value = '';

            if (controlOperatorUsers.length === 0) {
                // Facilitator and Control Operator are interchangeable
                Promise.all([
                    loadUsersByRole('CONTROL_OPERATOR'),
                    loadUsersByRole('FACILITATOR')
                ]).then(([operators, facilitators]) => {
                    const seen = new Set();
                    controlOperatorUsers = [];
                    [...operators, ...facilitators].forEach(u => {
                        if (!seen.has(u.mail || u.email)) {
                            seen.add(u.mail || u.email);
                            controlOperatorUsers.push(u);
                        }
                    });
                    displayControlOperatorList(controlOperatorUsers);
                });
            } else {
                displayControlOperatorList(controlOperatorUsers);
            }

            setTimeout(() => {
                const searchInput = document.getElementById('controlOperatorSearchInput');
                if (searchInput) searchInput.focus();
            }, 100);
        } else {
            closeControlOperatorDropdown();
        }
    }

    function closeControlOperatorDropdown() {
        const dropdown = document.getElementById('controlOperatorDropdown');
        dropdown.style.display = 'none';
        isControlOperatorDropdownOpen = false;
    }

    function displayControlOperatorList(users) {
        const usersList = document.getElementById('controlOperatorUsersList');
        if (!usersList) return;

        usersList.innerHTML = '';

        if (users.length === 0) {
            usersList.innerHTML = '<div class="no-users-message">No users found</div>';
            return;
        }

        const sortedUsers = [...users].sort((a, b) => a.displayName.localeCompare(b.displayName));

        sortedUsers.forEach(user => {
            const isSelected = selectedControlOperator && selectedControlOperator.mail === user.mail;
            const listItem = createCompactUserItem(user, isSelected);
            listItem.addEventListener('click', function() {
                selectControlOperator(user);
            });
            usersList.appendChild(listItem);
        });
    }

    function filterControlOperatorList() {
        const searchInput = document.getElementById('controlOperatorSearchInput');
        if (!searchInput) return;

        const query = searchInput.value.trim().toLowerCase();
        if (query === '') {
            displayControlOperatorList(controlOperatorUsers);
            return;
        }

        const filteredUsers = controlOperatorUsers.filter(user => {
            if (!user) return false;
            
            return (user.displayName && user.displayName.toLowerCase().startsWith(query)) ||
                   (user.username && user.username.toLowerCase().startsWith(query)) ||
                   (user.mail && user.mail.toLowerCase().startsWith(query));
        });

        displayControlOperatorList(filteredUsers);
    }

    function selectControlOperator(user) {
        selectedControlOperator = user;

        const input = document.getElementById('controlOperatorInput');
        if (input) input.value = user.displayName;

        const hiddenInput = document.getElementById('controlOperatorHidden');
        if (hiddenInput) hiddenInput.value = user.mail;

        displayControlOperatorList(controlOperatorUsers);
        closeControlOperatorDropdown();
    }

    // ========== SOQM LEAD FUNCTIONS ==========
    function toggleSoqmLeadDropdown() {
        if (!isAssignmentDropdownEditable('soqmLeadInput')) {
            return;
        }
        const dropdown = document.getElementById('soqmLeadDropdown');
        if (!isSoqmLeadDropdownOpen) {
            dropdown.style.display = 'block';
            isSoqmLeadDropdownOpen = true;

            const searchInput = document.getElementById('soqmLeadSearchInput');
            if (searchInput) searchInput.value = '';

            if (soqmLeadUsers.length === 0) {
                loadUsersByRole('SOQM_LEAD').then((users) => {
                    soqmLeadUsers = users;
                    displaySoqmLeadList(soqmLeadUsers);
                });
            } else {
                displaySoqmLeadList(soqmLeadUsers);
            }

            setTimeout(() => {
                const searchInput = document.getElementById('soqmLeadSearchInput');
                if (searchInput) searchInput.focus();
            }, 100);
        } else {
            closeSoqmLeadDropdown();
        }
    }

    function closeSoqmLeadDropdown() {
        const dropdown = document.getElementById('soqmLeadDropdown');
        dropdown.style.display = 'none';
        isSoqmLeadDropdownOpen = false;
    }

    function displaySoqmLeadList(users) {
        const usersList = document.getElementById('soqmLeadUsersList');
        if (!usersList) return;

        usersList.innerHTML = '';

        if (users.length === 0) {
            usersList.innerHTML = '<div class="no-users-message">No users found</div>';
            return;
        }

        const sortedUsers = [...users].sort((a, b) => a.displayName.localeCompare(b.displayName));

        sortedUsers.forEach(user => {
            const isSelected = selectedSoqmLead && selectedSoqmLead.mail === user.mail;
            const listItem = createCompactUserItem(user, isSelected);
            listItem.addEventListener('click', function() {
                selectSoqmLead(user);
            });
            usersList.appendChild(listItem);
        });
    }

    function filterSoqmLeadList() {
        const searchInput = document.getElementById('soqmLeadSearchInput');
        if (!searchInput) return;

        const query = searchInput.value.trim().toLowerCase();
        if (query === '') {
            displaySoqmLeadList(soqmLeadUsers);
            return;
        }

        const filteredUsers = soqmLeadUsers.filter(user => {
            if (!user) return false;
            
            return (user.displayName && user.displayName.toLowerCase().startsWith(query)) ||
                   (user.username && user.username.toLowerCase().startsWith(query)) ||
                   (user.mail && user.mail.toLowerCase().startsWith(query));
        });

        displaySoqmLeadList(filteredUsers);
    }

    function selectSoqmLead(user) {
        selectedSoqmLead = user;

        const input = document.getElementById('soqmLeadInput');
        if (input) input.value = user.displayName;

        const hiddenInput = document.getElementById('soqmLeadHidden');
        if (hiddenInput) hiddenInput.value = user.mail;

        displaySoqmLeadList(soqmLeadUsers);
        closeSoqmLeadDropdown();
    }

    // ========== PROCESS OWNER FUNCTIONS ==========
    function toggleProcessOwnerDropdown() {
        if (!isAssignmentDropdownEditable('processOwnerInput')) {
            return;
        }
        const dropdown = document.getElementById('processOwnerDropdown');
        if (!isProcessOwnerDropdownOpen) {
            dropdown.style.display = 'block';
            isProcessOwnerDropdownOpen = true;

            const searchInput = document.getElementById('processOwnerSearchInput');
            if (searchInput) searchInput.value = '';

            if (processOwnerUsers.length === 0) {
                loadUsersByRole('PROCESS_OWNER').then((users) => {
                    processOwnerUsers = users;
                    displayProcessOwnerList(processOwnerUsers);
                });
            } else {
                displayProcessOwnerList(processOwnerUsers);
            }

            setTimeout(() => {
                const searchInput = document.getElementById('processOwnerSearchInput');
                if (searchInput) searchInput.focus();
            }, 100);
        } else {
            closeProcessOwnerDropdown();
        }
    }

    function closeProcessOwnerDropdown() {
        const dropdown = document.getElementById('processOwnerDropdown');
        dropdown.style.display = 'none';
        isProcessOwnerDropdownOpen = false;
    }

    function displayProcessOwnerList(users) {
        const usersList = document.getElementById('processOwnerUsersList');
        if (!usersList) return;

        usersList.innerHTML = '';

        if (users.length === 0) {
            usersList.innerHTML = '<div class="no-users-message">No users found</div>';
            return;
        }

        const sortedUsers = [...users].sort((a, b) => a.displayName.localeCompare(b.displayName));

        sortedUsers.forEach(user => {
            const isSelected = selectedProcessOwner && selectedProcessOwner.mail === user.mail;
            const listItem = createCompactUserItem(user, isSelected);
            listItem.addEventListener('click', function() {
                selectProcessOwner(user);
            });
            usersList.appendChild(listItem);
        });
    }

    function filterProcessOwnerList() {
        const searchInput = document.getElementById('processOwnerSearchInput');
        if (!searchInput) return;

        const query = searchInput.value.trim().toLowerCase();
        if (query === '') {
            displayProcessOwnerList(processOwnerUsers);
            return;
        }

        const filteredUsers = processOwnerUsers.filter(user => {
            if (!user) return false;
            
            return (user.displayName && user.displayName.toLowerCase().startsWith(query)) ||
                   (user.username && user.username.toLowerCase().startsWith(query)) ||
                   (user.mail && user.mail.toLowerCase().startsWith(query));
        });

        displayProcessOwnerList(filteredUsers);
    }

    function selectProcessOwner(user) {
        selectedProcessOwner = user;

        const input = document.getElementById('processOwnerInput');
        if (input) input.value = user.displayName;

        const hiddenInput = document.getElementById('processOwnerHidden');
        if (hiddenInput) hiddenInput.value = user.mail;

        displayProcessOwnerList(processOwnerUsers);
        closeProcessOwnerDropdown();
    }

    // ========== CONTROL SHARED WITH FUNCTIONS ==========
    function toggleSharedWithDropdown() {
        if (!isAssignmentDropdownEditable('sharedWithInput')) {
            return;
        }
        const dropdown = document.getElementById('sharedWithDropdown');
        if (!isSharedWithDropdownOpen) {
            dropdown.style.display = 'block';
            isSharedWithDropdownOpen = true;

            const searchInput = document.getElementById('sharedWithSearchInput');
            if (searchInput) searchInput.value = '';

            if (sharedWithUsers.length === 0) {
                loadAllUsers().then((users) => {
                    sharedWithUsers = users;
                    displaySharedWithList(sharedWithUsers);
                });
            } else {
                displaySharedWithList(sharedWithUsers);
            }

            setTimeout(() => {
                const searchInput = document.getElementById('sharedWithSearchInput');
                if (searchInput) searchInput.focus();
            }, 100);
        } else {
            closeSharedWithDropdown();
        }
    }

    function closeSharedWithDropdown() {
        const dropdown = document.getElementById('sharedWithDropdown');
        dropdown.style.display = 'none';
        isSharedWithDropdownOpen = false;
    }

    function displaySharedWithList(users) {
        const usersList = document.getElementById('sharedWithUsersList');
        if (!usersList) return;

        usersList.innerHTML = '';

        if (users.length === 0) {
            usersList.innerHTML = '<div class="no-users-message">No users found</div>';
            return;
        }

        const sortedUsers = [...users].sort((a, b) => a.displayName.localeCompare(b.displayName));

        sortedUsers.forEach(user => {
            const isSelected = selectedSharedWithUsers.some(u => u.mail === user.mail);
            
            const listItem = document.createElement('div');
            listItem.className = 'list-group-item p-2';
            listItem.style.cursor = 'pointer';
            listItem.style.display = 'flex';
            listItem.style.alignItems = 'center';
            listItem.style.gap = '10px';
            listItem.innerHTML = `
                <input type="checkbox" 
                       ${isSelected ? 'checked' : ''} 
                       class="form-check-input"
                       style="margin: 0;">
                <div style="flex: 1;">
                    <div style="font-weight: 500;">${user.displayName}</div>
                    <div style="font-size: 12px; color: #666;">${user.mail}</div>
                </div>
            `;
            
            listItem.addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
                toggleSharedWithUser(user);
            });
            
            usersList.appendChild(listItem);
        });
    }

    function toggleSharedWithUser(user) {
        const index = selectedSharedWithUsers.findIndex(u => u.mail === user.mail);
        
        if (index === -1) {
            // Add user
            selectedSharedWithUsers.push(user);
        } else {
            // Remove user
            selectedSharedWithUsers.splice(index, 1);
        }
        
        updateSharedWithDisplay();
        updateSharedWithHidden();
        displaySharedWithList(sharedWithUsers);
    }

    function updateSharedWithDisplay() {
        const placeholder = document.getElementById('sharedWithPlaceholder');
        const tagsContainer = document.getElementById('sharedWithSelectedTags');
        
        if (selectedSharedWithUsers.length === 0) {
            placeholder.style.display = 'inline';
            tagsContainer.innerHTML = '';
        } else {
            placeholder.style.display = 'none';
            tagsContainer.innerHTML = '';
            
            selectedSharedWithUsers.forEach((user, index) => {
                const span = document.createElement('span');
                span.className = 'badge bg-primary';
                span.style.display = 'flex';
                span.style.alignItems = 'center';
                span.style.gap = '5px';
                
                const nameSpan = document.createElement('span');
                nameSpan.textContent = user.displayName;
                
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'btn-close btn-close-white';
                btn.setAttribute('aria-label', 'Remove');
                btn.style.marginLeft = '5px';
                btn.style.padding = '0';
                btn.style.fontSize = '12px';
                
                btn.addEventListener('click', function(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    removeSharedWithUser(user.mail);
                });
                
                span.appendChild(nameSpan);
                span.appendChild(btn);
                tagsContainer.appendChild(span);
            });
        }
    }

    function removeSharedWithUser(mail) {
        const index = selectedSharedWithUsers.findIndex(u => u.mail === mail);
        if (index !== -1) {
            selectedSharedWithUsers.splice(index, 1);
            updateSharedWithDisplay();
            updateSharedWithHidden();
            displaySharedWithList(sharedWithUsers);
        }
    }

    function updateSharedWithHidden() {
        const hiddenInput = document.getElementById('controlSharedWithHidden');
        if (hiddenInput) {
            hiddenInput.value = JSON.stringify(selectedSharedWithUsers.map(u => u.mail));
        }
    }

    function filterSharedWithList() {
        const searchInput = document.getElementById('sharedWithSearchInput');
        if (!searchInput) return;

        const query = searchInput.value.trim().toLowerCase();
        if (query === '') {
            displaySharedWithList(sharedWithUsers);
            return;
        }

        const filteredUsers = sharedWithUsers.filter(user => {
            if (!user) return false;
            
            return (user.displayName && user.displayName.toLowerCase().startsWith(query)) ||
                   (user.username && user.username.toLowerCase().startsWith(query)) ||
                   (user.mail && user.mail.toLowerCase().startsWith(query));
        });

        displaySharedWithList(filteredUsers);
    }

    function selectSharedWithUser(user) {
        // Legacy function - now using toggleSharedWithUser instead
        toggleSharedWithUser(user);
    }

    // ========== DATA LOADING FUNCTIONS ==========
    async function loadAssignmentData(controlId) {
        try {
            const response = await fetch('/api/control-assignment?controlId=' + controlId);
            if (response.ok) {
                const assignmentData = await response.json();

                if (assignmentData && assignmentData.controlId) {
                    // Загружаем пользователей если еще не загружены
                    if (allUsers.length === 0) {
                        await loadAllUsers();
                    }

                    // Facilitator
                    if (assignmentData.facilitator && Array.isArray(assignmentData.facilitator) && assignmentData.facilitator.length > 0) {
                        const user = allUsers.find(u => u.mail === assignmentData.facilitator[0]);
                        if (user) {
                            selectUser(user);
                        }
                    }

                    // Control Operator
                    if (assignmentData.controlOperator && Array.isArray(assignmentData.controlOperator) && assignmentData.controlOperator.length > 0) {
                        const user = allUsers.find(u => u.mail === assignmentData.controlOperator[0]);
                        if (user) {
                            selectControlOperator(user);
                        }
                    }

                    // SoQM Lead
                    if (assignmentData.soqmLead && Array.isArray(assignmentData.soqmLead) && assignmentData.soqmLead.length > 0) {
                        const user = allUsers.find(u => u.mail === assignmentData.soqmLead[0]);
                        if (user) {
                            selectSoqmLead(user);
                        }
                    }

                    // Process Owner
                    if (assignmentData.processOwner && Array.isArray(assignmentData.processOwner) && assignmentData.processOwner.length > 0) {
                        const user = allUsers.find(u => u.mail === assignmentData.processOwner[0]);
                        if (user) {
                            selectProcessOwner(user);
                        }
                    }

                    // Control Shared With - multiple users
                    if (assignmentData.controlSharedWith && Array.isArray(assignmentData.controlSharedWith) && assignmentData.controlSharedWith.length > 0) {
                        selectedSharedWithUsers = [];
                        assignmentData.controlSharedWith.forEach(email => {
                            const user = allUsers.find(u => u.mail === email);
                            if (user) {
                                selectedSharedWithUsers.push(user);
                            }
                        });
                        updateSharedWithDisplay();
                        updateSharedWithHidden();
                    }

                    // Даты
                    const form = document.getElementById('assignmentForm');
                    if (form) {
                        if (assignmentData.controlOperationDate) {
                            const dateInput = form.querySelector('input[name="controlOperationDate"]');
                            if (dateInput) {
                                if (dateInput.type === 'date') {
                                    dateInput.value = assignmentData.controlOperationDate;
                                } else if (window.QTrackerDate) {
                                    dateInput.value = window.QTrackerDate.formatDisplayDateFromIso(assignmentData.controlOperationDate);
                                }
                                dateInput.dataset.isoValue = assignmentData.controlOperationDate;
                            }
                        }
                        if (assignmentData.controlOperationDeadline) {
                            const deadlineInput = form.querySelector('input[name="controlOperationDeadline"]');
                            if (deadlineInput) {
                                if (deadlineInput.type === 'date') {
                                    deadlineInput.value = assignmentData.controlOperationDeadline;
                                } else if (window.QTrackerDate) {
                                    deadlineInput.value = window.QTrackerDate.formatDisplayDateFromIso(assignmentData.controlOperationDeadline);
                                }
                                deadlineInput.dataset.isoValue = assignmentData.controlOperationDeadline;
                            }
                        }
                        if (assignmentData.nextControlOperationDate) {
                            const nextDateInput = form.querySelector('input[name="nextControlOperationDate"]');
                            if (nextDateInput) {
                                if (nextDateInput.type === 'date') {
                                    nextDateInput.value = assignmentData.nextControlOperationDate;
                                } else if (window.QTrackerDate) {
                                    nextDateInput.value = window.QTrackerDate.formatDisplayDateFromIso(assignmentData.nextControlOperationDate);
                                }
                                nextDateInput.dataset.isoValue = assignmentData.nextControlOperationDate;
                            }
                        }
                        normalizeAssignmentDateFieldsForDisplay();
                        updateCalculatedDates();
                    }
                }
            }
        } catch (error) {
            console.error('Error loading assignment data:', error);
        }
    }

    async function loadUsers() {
        try {
            await loadAllUsers();
            const controlId = document.querySelector('input[name="id"]').value;
            loadAssignmentData(controlId);
        } catch (error) {
            console.error('Error loading users:', error);
        }
    }

    async function loadDetailsData(controlId) {
        try {
            const response = await fetch('/api/control-details?controlId=' + controlId);
            if (response.ok) {
                const detailsData = await response.json();
                detailsDataCache = detailsData || {};

                if (detailsDataCache && detailsDataCache.controlId) {
                    const form = document.getElementById('detailsForm');
                    if (form) {
                        Object.keys(detailsDataCache).forEach(key => {
                            const field = form.querySelector(`[name="${key}"]`);
                            if (field && field.type !== 'file' && detailsDataCache[key] !== undefined && detailsDataCache[key] !== null) {
                                field.value = detailsDataCache[key];
                            }
                        });
                        const stepsField = form.querySelector('textarea[name="controlStepsPerformed"]');
                        if (stepsField) {
                            stepsField.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                        const soqmField = form.querySelector('textarea[name="soqmHeadComments"]');
                        if (soqmField) {
                            soqmField.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                        const poField = form.querySelector('textarea[name="processOwnerComments"]');
                        if (poField) {
                            poField.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                    }
                }
            }
        } catch (error) {
            console.error('Error loading details data:', error);
        }
    }

    async function loadDocumentsData(controlId) {
        try {
            const response = await fetch('/api/control-documents?controlId=' + controlId);
            if (response.ok) {
                const documentsData = await response.json();

                if (documentsData && documentsData.controlId) {
                    const form = document.getElementById('documentsForm');
                    if (form) {
                        if (documentsData.soqmDevelopmentMaterials) {
                            form.querySelector('[name="soqmDevelopmentMaterials"]').value = documentsData.soqmDevelopmentMaterials;
                        }
                    }
                }
            }
        } catch (error) {
            console.error('Error loading documents data:', error);
        }
    }

    // ========== FORM MODE FUNCTIONS ==========
    function initializeReadOnlyMode() {
        const allInputs = document.querySelectorAll('#detailsForm input, #assignmentForm input, #documentsForm input');
        const allTextareas = document.querySelectorAll('#detailsForm textarea, #assignmentForm textarea');
        const allSelects = document.querySelectorAll('#detailsForm select, #assignmentForm select, #documentsForm select');

        allInputs.forEach(input => {
            if (!input.classList.contains('readonly-field')) {
                input.classList.add('readonly-field');
                input.readOnly = true;
            }
        });

        allTextareas.forEach(textarea => {
            if (!textarea.classList.contains('readonly-field')) {
                textarea.classList.add('readonly-field');
                textarea.readOnly = true;
            }
        });

        allSelects.forEach(select => {
            if (!select.classList.contains('readonly-field')) {
                select.classList.add('readonly-field', 'readonly-select');
                select.disabled = true;
                select.style.pointerEvents = 'none';
            }
        });

        const dropdownInputs = [
            'facilitatorInput',
            'controlOperatorInput',
            'soqmLeadInput',
            'processOwnerInput',
            'sharedWithInput'
        ];

        dropdownInputs.forEach(id => {
            const input = document.getElementById(id);
            if (input) {
                input.classList.add('readonly-field');
                input.readOnly = true;
            }
        });
    }

    function enableControlStepsPerformedField() {
        const stepsField = document.querySelector('textarea[name="controlStepsPerformed"]');
        if (!stepsField) {
            return;
        }
        stepsField.classList.remove('readonly-field', 'readonly-select');
        stepsField.classList.add('editable-field', 'editable-select');
        stepsField.readOnly = false;
        stepsField.disabled = false;
        stepsField.removeAttribute('readonly');
        stepsField.removeAttribute('disabled');
        stepsField.style.pointerEvents = 'auto';
        stepsField.style.backgroundColor = '';
    }

    function enableProcessOwnerCommentsField() {
        const commentsField = document.querySelector('textarea[name="processOwnerComments"]');
        if (!commentsField) {
            return;
        }
        commentsField.classList.remove('readonly-field', 'readonly-select');
        commentsField.classList.add('editable-field', 'editable-select');
        commentsField.readOnly = false;
        commentsField.disabled = false;
        commentsField.removeAttribute('readonly');
        commentsField.removeAttribute('disabled');
        commentsField.style.pointerEvents = 'auto';
        commentsField.style.backgroundColor = '';
    }

function makeAllFormsEditable() {
    console.log('=== MAKE ALL FORMS EDITABLE ===');

    makeAllFormsReadOnly();

    if (stepsPerformedEditOnly) {
        console.log('✅ Field-level edit mode: enabling controlStepsPerformed only');
        enableControlStepsPerformedField();
        normalizeAssignmentDateFieldsForDisplay();
        return;
    }

    if (processOwnerCommentsEditOnly) {
        console.log('✅ Process Owner edit mode: enabling processOwnerComments only');
        enableProcessOwnerCommentsField();
        normalizeAssignmentDateFieldsForDisplay();
        return;
    }
    if (!fullEditEnabled) {
        normalizeAssignmentDateFieldsForDisplay();
        return;
    }


    // 1. CONTROL TAB - делаем редактируемой
    console.log('🔄 Processing Control tab fields...');
    const controlFields = document.querySelectorAll('#controlForm input, #controlForm select, #controlForm textarea');
    controlFields.forEach(field => {
        // Пропускаем Control ID поле (оно всегда readonly)
        if (field.name === 'controlId' || field.id === 'controlId') {
            console.log(`  ⏭️ Skipping controlId field: ${field.name}`);
            return;
        }

        console.log(`  📝 Processing field: ${field.name || field.id}`);
        console.log(`    Before - readonly: ${field.readOnly}, disabled: ${field.disabled}, classes: ${field.className}`);

        // Удаляем readonly классы
        field.classList.remove('readonly-field', 'readonly-select');

        // Добавляем editable классы
        field.classList.add('editable-field', 'editable-select');

        // Убираем атрибуты readonly и disabled
        field.removeAttribute('readonly');
        field.readOnly = false;
        field.disabled = false;

        // Включаем взаимодействие
        field.style.pointerEvents = 'auto';
        field.style.backgroundColor = ''; // Сбрасываем фон

        console.log(`    After - readonly: ${field.readOnly}, disabled: ${field.disabled}, classes: ${field.className}`);
    });

    // 2. DETAILS TAB
    console.log('🔄 Processing Details tab fields...');
    const detailsFields = document.querySelectorAll('#detailsForm input, #detailsForm textarea, #detailsForm select');
    detailsFields.forEach(field => {
        field.classList.remove('readonly-field', 'readonly-select');
        field.classList.add('editable-field', 'editable-select');
        field.readOnly = false;
        field.disabled = false;
        field.style.pointerEvents = 'auto';
        field.style.backgroundColor = '';
        
        // Enable file inputs
        if (field.type === 'file') {
            const zone = field.closest('.kpmg-upload-zone');
            if (zone) zone.removeAttribute('data-disabled');
        }
    });

    // 3. ASSIGNMENT TAB
    console.log('🔄 Processing Assignment tab fields...');
    const assignmentFields = document.querySelectorAll('#assignmentForm input, #assignmentForm select');
    const alwaysReadonlyFields = [];
    const canEditAssignment = true;

    assignmentFields.forEach(field => {
        if (!canEditAssignment || alwaysReadonlyFields.includes(field.name)) {
            console.log(`  ⏭️ Skipping always readonly field: ${field.name}`);
            field.classList.add('readonly-field');
            field.readOnly = true;
            field.disabled = true;
            field.style.pointerEvents = 'none';
            field.style.backgroundColor = '#e9ecef';
            return;
        }

        field.classList.remove('readonly-field', 'readonly-select');
        field.classList.add('editable-field', 'editable-select');
        field.readOnly = false;
        field.disabled = false;
        field.style.pointerEvents = 'auto';
        field.style.backgroundColor = '';
    });

    // 4. DOCUMENTS TAB
    console.log('🔄 Processing Documents tab fields...');
    const documentsFields = document.querySelectorAll('#documentsForm input, #documentsForm select');
    documentsFields.forEach(field => {
        field.classList.remove('readonly-field', 'readonly-select');
        field.classList.add('editable-field', 'editable-select');
        field.readOnly = false;
        field.disabled = false;
        field.style.pointerEvents = 'auto';
        field.style.backgroundColor = '';
        
        // Enable file inputs
        if (field.type === 'file') {
            const zone = field.closest('.kpmg-upload-zone');
            if (zone) zone.removeAttribute('data-disabled');
        }
    });

    console.log('🔄 Processing dropdown inputs...');
    const dropdownInputs = [
        'facilitatorInput',
        'controlOperatorInput',
        'soqmLeadInput',
        'processOwnerInput',
        'sharedWithInput'
    ];

    dropdownInputs.forEach(id => {
        const input = document.getElementById(id);
        if (input) {
            console.log(`  📝 Processing dropdown: ${id}`);
            if (canEditAssignment) {
                input.classList.remove('readonly-field');
                input.readOnly = false;
                input.style.pointerEvents = 'auto';
                input.style.backgroundColor = '';
            } else {
                input.classList.add('readonly-field');
                input.readOnly = true;
                input.style.pointerEvents = 'none';
                input.style.backgroundColor = '#e9ecef';
                input.style.cursor = 'not-allowed';
            }
        }
    });

    console.log('✅ All forms are now editable');
    normalizeAssignmentDateFieldsForDisplay();
    updateCalculatedDates();

    // 6. Проверка результата
    console.log('=== FINAL CHECK ===');
    console.log('Control Frequency field:');
    const controlFreq = document.querySelector('[name="controlFrequency"]');
    if (controlFreq) {
        console.log('  Element:', controlFreq);
        console.log('  Readonly:', controlFreq.readOnly);
        console.log('  Disabled:', controlFreq.disabled);
        console.log('  Classes:', controlFreq.className);
        console.log('  Style pointerEvents:', controlFreq.style.pointerEvents);
    }
}

function saveControlData(controlId) {
    console.log('=== SAVE CONTROL DATA ===');

    const controlForm = document.getElementById('controlForm');
    if (!controlForm) {
        return Promise.reject('Control form not found');
    }

    const getControlValue = (selector) => {
        const element = controlForm.querySelector(selector);
        return element ? element.value : '';
    };

    const controlData = {
        controlFrequency: getControlValue('[name="controlFrequency"]'),
        controlCategory: getControlValue('[name="controlCategory"]'),
        controlType: getControlValue('[name="controlType"]'),
        component: getControlValue('[name="component"]'),
        operatedBy: getControlValue('[name="operatedBy"]'),
        controlStatus: getControlValue('[name="controlStatus"]'),
        priority: getControlValue('[name="priority"]'),
        nonAuditServicesApplicability: getControlValue('[name="nonAuditServicesApplicability"]'),
        controlDescription: getControlValue('[name="controlDescription"]'),
        prp: getControlValue('[name="prp"]')
    };

    console.log('Control data to send:', controlData);

    return fetch('/api/controls/' + controlId, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        },
        body: JSON.stringify(controlData)
    })
    .then(async response => {
        console.log('📥 Control save response status:', response.status);

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Control save failed: ${errorText}`);
        }

        const responseText = await response.text();
        console.log('📥 Control save response:', responseText);

        if (!responseText || responseText.trim() === '') {
            return { success: true };
        }

        try {
            return JSON.parse(responseText);
        } catch (e) {
            return { success: true, message: responseText };
        }
    })
.then(data => {
    console.log('✅ Control saved successfully:', data);

    // Показываем алерт
    showAppModal({
        variant: 'success',
        title: 'Saved Successfully',
        message: 'Control information has been saved'
    });

    return data;
})
    .catch(error => {
        console.error('❌ Error saving control:', error);
        alert('Error saving control: ' + error.message);
        throw error;
    });
}

    function makeAllFormsReadOnly() {
        const allInputs = document.querySelectorAll('#controlForm input, #detailsForm input, #assignmentForm input, #documentsForm input');
        const allTextareas = document.querySelectorAll('#controlForm textarea, #detailsForm textarea, #assignmentForm textarea');
        const allSelects = document.querySelectorAll('#controlForm select, #detailsForm select, #assignmentForm select, #documentsForm select');

        allInputs.forEach(field => {
            if (field.id !== 'controlId') {
                field.classList.remove('editable-field');
                field.classList.add('readonly-field');
                field.readOnly = true;
                
                // Disable file inputs completely and clear selection
                if (field.type === 'file') {
                    field.disabled = true;
                    field.value = ''; // Clear file selection
                    
                    // Disable upload zone
                    const zone = field.closest('.kpmg-upload-zone');
                    if (zone) {
                        zone.setAttribute('data-disabled', 'true');
                    }
                    
                    // Clear "Selected X file(s)" display, but keep existing uploaded files
                    if (field.id === 'attachmentDetailsInput') {
                        const infoElement = document.getElementById('detailsFileInfo');
                        if (infoElement && infoElement.textContent.includes('Selected')) {
                            infoElement.textContent = ''; // Clear selection display
                        }
                    } else if (field.id === 'attachmentDocumentsInput') {
                        const infoElement = document.getElementById('documentsFileInfo');
                        if (infoElement && infoElement.textContent.includes('Selected')) {
                            infoElement.textContent = ''; // Clear selection display
                        }
                    }
                }
            }
        });

        allTextareas.forEach(field => {
            field.classList.remove('editable-field');
            field.classList.add('readonly-field');
            field.readOnly = true;
        });

        allSelects.forEach(field => {
            field.classList.remove('editable-field', 'editable-select');
            field.classList.add('readonly-field', 'readonly-select');
            field.disabled = true;
            field.style.pointerEvents = 'none';
        });

        const deadlineField = document.querySelector('input[name="controlOperationDeadline"]');
        const nextDateField = document.querySelector('input[name="nextControlOperationDate"]');

        if (deadlineField) {
            deadlineField.classList.add('readonly-field');
            deadlineField.readOnly = true;
            deadlineField.disabled = true;
        }

        if (nextDateField) {
            nextDateField.classList.add('readonly-field');
            nextDateField.readOnly = true;
            nextDateField.disabled = true;
        }

        // Re-apply readonly style/behavior for custom dropdown display blocks (div-based fields)
        const dropdownInputs = [
            'facilitatorInput',
            'controlOperatorInput',
            'soqmLeadInput',
            'processOwnerInput',
            'sharedWithInput'
        ];

        dropdownInputs.forEach(id => {
            const input = document.getElementById(id);
            if (!input) return;
            input.classList.add('readonly-field');
            input.setAttribute('aria-disabled', 'true');
            input.style.pointerEvents = 'none';
            input.style.cursor = 'not-allowed';
            input.style.backgroundColor = '#e9ecef';
        });

        normalizeAssignmentDateFieldsForDisplay();
    }

    function resetAllForms() {
        const controlForm = document.getElementById('controlForm');
        if (controlForm) controlForm.reset();

        const detailsForm = document.getElementById('detailsForm');
        if (detailsForm) detailsForm.reset();

        const assignmentForm = document.getElementById('assignmentForm');
        if (assignmentForm) assignmentForm.reset();

        const documentsForm = document.getElementById('documentsForm');
        if (documentsForm) documentsForm.reset();
    }

    function switchToEditMode() {
        captureEditModeSnapshot();
        document.body.classList.add('edit-mode-active');

        const actionButtons = document.querySelector('.action-buttons');
        if (actionButtons) actionButtons.classList.add('d-none');

        const editNavPanel = document.getElementById('editNavPanel');
        if (editNavPanel) {
            editNavPanel.classList.remove('d-none');
        }

        const editBtn = document.getElementById('editBtn');
        if (editBtn) editBtn.classList.add('d-none');

        makeAllFormsEditable();
    }

    function switchToReadOnlyMode() {
        restoreFromEditModeSnapshot();
        document.body.classList.remove('edit-mode-active');

        const actionButtons = document.querySelector('.action-buttons');
        if (actionButtons) actionButtons.classList.remove('d-none');

        const editNavPanel = document.getElementById('editNavPanel');
        if (editNavPanel) {
            editNavPanel.classList.add('d-none');
        }

        const editBtn = document.getElementById('editBtn');
        if (editBtn) {
            editBtn.classList.remove('d-none');
            editBtn.textContent = 'Edit';
            editBtn.classList.remove('btn-success', 'btn-primary');
            editBtn.classList.add('btn-outline-secondary');
        }

        makeAllFormsReadOnly();
    }

    function checkControlIdUnique(newControlId, currentControlId) {
        if (newControlId === currentControlId) {
            return Promise.resolve(true);
        }

        return fetch('/api/controls/check-id-unique?controlId=' + encodeURIComponent(newControlId))
            .then(response => {
                if (!response.ok) {
                    throw new Error('Network error');
                }
                return response.json();
            })
            .then(data => data.unique)
            .catch(error => {
                console.error('Error checking ID uniqueness:', error);
                return false;
            });
    }

function renameControlId(newControlId) {
        const controlPrimaryKey = document.querySelector('input[name="id"]').value;
        console.log('Primary key to rename:', controlPrimaryKey);
        console.log('New control_id value:', newControlId);

        return fetch('/api/controls/' + controlPrimaryKey + '/rename-id', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ newControlId: newControlId })
        })
        .then(response => {
            if (response.ok) {
                return response.json();
            } else {
                return response.text().then(text => {
                    throw new Error(text || 'Failed to rename Control ID');
                });
            }
        });
    }

    // ========== VALIDATION FUNCTIONS ==========
    function isValidEmail(email) {
        const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        return emailRegex.test(email);
    }

    function parseIsoDate(value) {
        if (window.QTrackerDate && typeof window.QTrackerDate.parseIsoDate === 'function') {
            return window.QTrackerDate.parseIsoDate(value);
        }
        return null;
    }

    function toIsoDate(date) {
        if (window.QTrackerDate && typeof window.QTrackerDate.toIsoDate === 'function') {
            return window.QTrackerDate.toIsoDate(date);
        }
        return '';
    }

    function isValidDate(dateString) {
        if (!dateString) return true;
        return parseIsoDate(dateString) !== null;
    }

    function setDateFieldValue(input, isoDateValue) {
        if (!input) {
            return;
        }
        if (!isoDateValue) {
            input.value = '';
            return;
        }
        if (input.type === 'date') {
            input.value = isoDateValue;
            return;
        }
        if (window.QTrackerDate) {
            input.value = window.QTrackerDate.formatDisplayDateFromIso(isoDateValue) || isoDateValue;
            return;
        }
        input.value = isoDateValue;
    }

    function formatDateDisplay(value) {
        if (!value) {
            return '';
        }
        if (window.QTrackerDate && typeof window.QTrackerDate.formatDateDisplay === 'function') {
            return window.QTrackerDate.formatDateDisplay(value) || '';
        }
        const trimmed = String(value).trim();
        const isoMatch = trimmed.match(/^(\d{4})-(\d{2})-(\d{2})$/);
        if (isoMatch) {
            return `${isoMatch[3]}.${isoMatch[2]}.${isoMatch[1]}`;
        }
        return trimmed;
    }

    function normalizeAssignmentDateFieldsForDisplay() {
        const operationDateInput = document.querySelector('input[name="controlOperationDate"]');
        const deadlineInput = document.querySelector('input[name="controlOperationDeadline"]');
        const nextDateInput = document.querySelector('input[name="nextControlOperationDate"]');

        [deadlineInput, nextDateInput].forEach((input) => {
            if (!input) return;
            const isoValue = formatDateForApi(input.value || input.dataset.isoValue || '');
            if (!isoValue) return;
            input.dataset.isoValue = isoValue;
            input.type = 'text';
            input.value = formatDateDisplay(isoValue);
        });

        if (!operationDateInput) return;

        const isReadOnly = operationDateInput.readOnly || operationDateInput.disabled || operationDateInput.classList.contains('readonly-field');
        const isoValue = formatDateForApi(operationDateInput.value || operationDateInput.dataset.isoValue || '');
        if (!isoValue) return;

        operationDateInput.dataset.isoValue = isoValue;
        if (isReadOnly) {
            operationDateInput.type = 'text';
            operationDateInput.value = formatDateDisplay(isoValue);
        } else {
            operationDateInput.type = 'date';
            operationDateInput.value = isoValue;
        }
    }

    function formatDateForApi(dateString) {
        if (!dateString || dateString.trim() === '') {
            return null;
        }

        try {
            const trimmed = dateString.trim();
            const displayDate = window.QTrackerDate && typeof window.QTrackerDate.parseDisplayDate === 'function'
                ? window.QTrackerDate.parseDisplayDate(trimmed)
                : null;
            const date = displayDate || parseIsoDate(trimmed);
            if (!date) {
                return null;
            }
            return toIsoDate(date);
        } catch (error) {
            console.error('Error formatting date:', error);
            return null;
        }
    }

function showRequiredFieldMessage(message, field) {
    if (window.showAppModal) {
        showAppModal({
            variant: 'warning',
            title: 'Missing Required Field',
            message: message,
            autoCloseMs: 0
        });
    } else {
        alert(message);
    }
    if (field && typeof field.focus === 'function') {
        field.focus();
    }
}

    function isBlankValue(value) {
        return value === null || value === undefined || String(value).trim() === '';
    }

    function validateControlSaveRequiredFields() {
        const controlForm = document.getElementById('controlForm');
        const assignmentForm = document.getElementById('assignmentForm');

        const requiredFields = [
            { label: 'Control ID', field: controlForm?.querySelector('[name="controlId"]') },
            { label: 'Control Frequency', field: controlForm?.querySelector('[name="controlFrequency"]') },
            { label: 'Control Type', field: controlForm?.querySelector('[name="controlType"]') },
            { label: 'Component', field: controlForm?.querySelector('[name="component"]') },
            { label: 'Operated By', field: controlForm?.querySelector('[name="operatedBy"]') },
            { label: 'Control Status', field: controlForm?.querySelector('[name="controlStatus"]') },
            { label: 'Priority', field: controlForm?.querySelector('[name="priority"]') },
            {
                label: 'Non-Audit Services Control Applicability',
                field: controlForm?.querySelector('[name="nonAuditServicesApplicability"]')
            },
            {
                label: 'Facilitator',
                field: document.getElementById('facilitatorInput'),
                valueField: assignmentForm?.querySelector('#facilitatorHidden')
            },
            {
                label: 'Control Operator',
                field: document.getElementById('controlOperatorInput'),
                valueField: assignmentForm?.querySelector('#controlOperatorHidden')
            },
            {
                label: 'SoQM Lead / Delegate',
                field: document.getElementById('soqmLeadInput'),
                valueField: assignmentForm?.querySelector('#soqmLeadHidden')
            },
            {
                label: 'Process Owner',
                field: document.getElementById('processOwnerInput'),
                valueField: assignmentForm?.querySelector('#processOwnerHidden')
            },
            {
                label: 'Control Operation Date',
                field: assignmentForm?.querySelector('[name="controlOperationDate"]')
            }
        ];

        requiredFields.forEach(({ field }) => {
            if (field) {
                field.classList.remove('is-invalid');
            }
        });

        let firstInvalid = null;
        requiredFields.forEach(({ field, valueField, label }) => {
            const valueSource = valueField || field;
            if (!valueSource) {
                return;
            }
            const value = valueSource.value;
            if (isBlankValue(value)) {
                if (field) {
                    field.classList.add('is-invalid');
                }
                if (!firstInvalid) {
                    firstInvalid = { field, label };
                }
            }
        });

        if (firstInvalid) {
            showRequiredFieldMessage(`${firstInvalid.label} is required.`, firstInvalid.field);
            return false;
        }

        return true;
    }

function saveControlChanges() {
    console.log('=== START SAVE CONTROL CHANGES ===');

    const controlId = document.querySelector('input[name="id"]').value;
    if (!controlId) {
        return Promise.reject('Control ID not found');
    }

    console.log('🔄 SAVING ALL 4 TABS...');

    // 1. Сохраняем Control
    return saveControlData(controlId)
        .then(() => {
            console.log('✅ Control tab saved');
            // 2. Сохраняем Assignment
            return saveAssignmentData(controlId);
        })
        .then(() => {
            console.log('✅ Assignment tab saved');
            // 3. Сохраняем Details
            return saveDetailsData(controlId);
        })
        .then(() => {
            console.log('✅ Details tab saved');
            // 4. Сохраняем Documents
            return saveDocumentsData(controlId);
        })
        .then(() => {
            console.log('✅ Documents tab saved');
            console.log('🎉 ALL 4 TABS SAVED SUCCESSFULLY!');

            // Общий алерт успеха
            showAppModal({
                variant: 'success',
                title: 'Saved Successfully',
                message: 'All control data has been saved',
                autoCloseMs: 2500,
                redirectUrl: '/view-control/' + controlId
            });

            return { success: true };
        })
        .catch(error => {
            console.error('❌ Error saving control data:', error);

            showAppModal({
                variant: 'error',
                title: 'Save Failed',
                message: 'Error saving control: ' + error.message,
                autoCloseMs: 0
            });

            throw error; // Пробрасываем ошибку чтобы кнопка Save восстановилась
        });
}

function saveAssignmentData(controlId) {
    console.log('=== SAVE ASSIGNMENT DATA ===');

    // Получаем email значения
    const getEmailValue = (id) => {
        const element = document.getElementById(id);
        const value = element ? element.value.trim() : '';
        return value ? [value] : [];
    };

    const facilitator = getEmailValue('facilitatorHidden');
    const controlOperator = getEmailValue('controlOperatorHidden');
    const soqmLead = getEmailValue('soqmLeadHidden');
    const processOwner = getEmailValue('processOwnerHidden');
    
    // Control Shared With - get multiple users
    const controlSharedWithElement = document.getElementById('controlSharedWithHidden');
    let controlSharedWith = [];
    if (controlSharedWithElement && controlSharedWithElement.value) {
        try {
            const parsed = JSON.parse(controlSharedWithElement.value);
            controlSharedWith = Array.isArray(parsed) ? parsed : [];
        } catch (e) {
            controlSharedWith = [];
        }
    }

    // Получаем даты
    const getDateValue = (name) => {
        const element = document.querySelector(`input[name="${name}"]`);
        const value = element ? element.value : null;
        return formatDateForApi(value);
    };

    const controlOperationDate = getDateValue('controlOperationDate');
    const controlOperationDeadline = getDateValue('controlOperationDeadline');
    const nextControlOperationDate = getDateValue('nextControlOperationDate');

    // Проверяем controlId
    const numericControlId = parseInt(controlId, 10);
    if (isNaN(numericControlId)) {
        return Promise.reject('Invalid Control ID');
    }

    // Подготавливаем данные
    const assignmentData = {
        controlId: numericControlId,
        facilitator: facilitator,
        controlOperator: controlOperator,
        soqmLead: soqmLead,
        processOwner: processOwner,
        controlSharedWith: controlSharedWith,
        controlOperationDate: controlOperationDate,
        controlOperationDeadline: controlOperationDeadline,
        nextControlOperationDate: nextControlOperationDate
    };

    console.log('📤 Sending assignment data:', assignmentData);

    // Отправляем запрос
    return fetch('/api/control-assignment', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        },
        body: JSON.stringify(assignmentData)
    })
    .then(async response => {
        console.log('📥 Response status:', response.status);
        console.log('📥 Response headers:', Array.from(response.headers.entries()));

        const responseText = await response.text();
        console.log('📥 Response body length:', responseText.length);
        console.log('📥 Response body (first 500 chars):', responseText.substring(0, 500));

        if (!response.ok) {
            let errorMessage = `Server error (${response.status})`;

            try {
                const errorJson = JSON.parse(responseText);
                errorMessage = errorJson.message ||
                              errorJson.error ||
                              errorJson.details ||
                              responseText;
            } catch (e) {
                errorMessage = responseText || `Server error (${response.status})`;
            }

            console.error('❌ Server error:', errorMessage);
            throw new Error(errorMessage);
        }

        // Если ответ пустой или не JSON - возвращаем success
        if (!responseText || responseText.trim() === '') {
            console.log('📥 Empty response, returning success');
            return { success: true };
        }

        // Пробуем распарсить JSON
        try {
            const data = JSON.parse(responseText);
            console.log('📥 Parsed JSON response:', data);
            return data;
        } catch (e) {
            console.warn('⚠️ Response is not valid JSON, treating as success');
            return { success: true, message: responseText };
        }
    })
    .then(data => {
        console.log('✅ Assignment saved successfully, response data:', data);

        // Upload file attachments if any
        if (window.uploadAttachments) {
            console.log('📤 Uploading file attachments...');
            window.uploadAttachments();
        }

        // Показываем алерт
        showAppModal({
            variant: 'success',
            title: 'Saved Successfully',
            message: 'Assignment data has been saved'
        });

        // Редирект через 2 секунды (после закрытия алерта)

        updateCalculatedDates();
        return data;
    })
    .catch(error => {
        console.error('❌ Save error:', error);

        let userMessage = 'Error saving assignment data. ';

        if (error.message.includes('1 saves failed')) {
            userMessage = 'Validation failed on server. Please check all required fields.';
        } else if (error.message.includes('400')) {
            userMessage = 'Bad request. Please check your data.';
        } else if (error.message.includes('500')) {
            userMessage = 'Server error. Please try again later.';
        } else {
            userMessage += error.message;
        }

        alert(userMessage);
        throw error;
    });
}

function saveDetailsData(controlId) {
    console.log('=== SAVE DETAILS DATA ===');

    const detailsData = buildDetailsPayload(controlId);
    if (!detailsData) {
        return Promise.reject('Details form not found');
    }

    console.log('Details data to send:', detailsData);

    return fetch('/api/control-details', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(detailsData)
    })
    .then(async response => {
        console.log('📥 Details response status:', response.status);

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Details save failed: ${errorText}`);
        }

        const responseText = await response.text();
        console.log('📥 Details response length:', responseText.length);

        if (!responseText || responseText.trim() === '') {
            return { success: true };
        }

        try {
            return JSON.parse(responseText);
        } catch (e) {
            return { success: true, message: responseText };
        }
    })
    .then(data => {
        console.log('✅ Details saved successfully');
        detailsDataCache = { ...(detailsDataCache || {}), ...detailsData };

        // Upload file attachments if any
        if (window.uploadAttachments) {
            console.log('📤 Uploading file attachments...');
            window.uploadAttachments();
        }

        showAppModal({
            variant: 'success',
            title: 'Saved Successfully',
            message: 'Details have been saved'
        });

        // Редирект через 2.1 секунды

        return data;
    })
    .catch(error => {
        console.error('❌ Error saving details:', error);

        showAppModal({
            variant: 'error',
            title: 'Save Failed',
            message: 'Error saving details: ' + error.message,
            autoCloseMs: 0
        });

        throw error;
    });
}

function saveDocumentsData(controlId) {
    console.log('=== SAVE DOCUMENTS DATA ===');

    const documentsForm = document.getElementById('documentsForm');
    if (!documentsForm) {
        console.log('⚠️ Documents form not found, skipping');
        return Promise.resolve({ success: true, skipped: true });
    }

    const getDocumentsValue = (selector) => {
        const element = documentsForm.querySelector(selector);
        return element ? element.value : '';
    };

    const documentsData = {
        controlId: parseInt(controlId),
        soqmDevelopmentMaterials: getDocumentsValue('[name="soqmDevelopmentMaterials"]')
    };

    console.log('Documents data to send:', documentsData);

    return fetch('/api/control-documents', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(documentsData)
    })
    .then(async response => {
        console.log('📥 Documents response status:', response.status);

        if (!response.ok) {
            const errorText = await response.text().catch(() => 'Unknown error');
            console.error('❌ Documents save failed:', errorText);
            throw new Error(`Documents save failed: ${errorText}`);
        }

        let responseText;
        try {
            responseText = await response.text();
        } catch (e) {
            console.log('⚠️ Could not read response text:', e.message);
            responseText = '';
        }

        console.log('📥 Documents response length:', responseText.length);

        if (!responseText || responseText.trim() === '') {
            console.log('📥 Empty documents response, returning success');
            return { success: true };
        }

        try {
            const parsed = JSON.parse(responseText);
            console.log('📥 Parsed documents response:', parsed);
            return parsed;
        } catch (e) {
            console.warn('⚠️ Documents response is not JSON, returning success');
            return { success: true, rawResponse: responseText };
        }
    })
.then(data => {
    console.log('✅ Documents saved successfully, response:', data);

    // Upload file attachments if any
    if (window.uploadAttachments) {
        console.log('📤 Uploading file attachments...');
        window.uploadAttachments();
    }

    showAppModal({
        variant: 'success',
        title: 'Saved Successfully',
        message: 'Documents have been saved'
    });

    // Редирект через 2.1 секунды

    return data;
})
    .catch(error => {
        console.error('❌ Unexpected error in saveDocumentsData:', error);

        showAppModal({
            variant: 'error',
            title: 'Save Failed',
            message: 'Error saving documents: ' + error.message,
            autoCloseMs: 0
        });

        // Не бросаем ошибку дальше
        return { success: false, caughtError: error.message };
    });
}


    function updateCalculatedDates() {
        const operationDateInput = document.querySelector('input[name="controlOperationDate"]');
        if (!operationDateInput) {
            return;
        }
        const isoValue = formatDateForApi(operationDateInput.value || operationDateInput.dataset.isoValue || '');
        if (!isoValue) {
            return;
        }
        const operationDate = parseIsoDate(isoValue);
        if (!operationDate) {
            return;
        }
        const controlFrequency = document.querySelector('#controlForm [name="controlFrequency"]')?.value
            || document.querySelector('[name="controlFrequency"]')?.value;
        applyAssignmentDatePreview(operationDate, controlFrequency);
        console.log('Updated calculated dates');
    }

    function normalizeControlFrequency(controlFrequency) {
        if (!controlFrequency) {
            return null;
        }
        const normalized = controlFrequency.toLowerCase().replace(/\s+/g, ' ').trim();
        const compact = normalized.replace(/[-\s]/g, '');

        if (normalized.includes('recurr')) {
            return 'recurring';
        }
        if (normalized.includes('quarter')) {
            return 'quarterly';
        }
        if (normalized.includes('month')) {
            return 'monthly';
        }
        if ((normalized.includes('ad') && normalized.includes('hoc'))
            || normalized.includes('as-required')
            || normalized.includes('at least annually')) {
            return 'ad-hoc';
        }
        if (compact.includes('semiannual')) {
            return 'semi annual';
        }
        if (normalized.includes('annual') || normalized.includes('annually')) {
            return 'annual';
        }
        return null;
    }

    // UI regression examples (matches ControlScheduleCalculator):
    // OperationDate=2026-02-06
    // Monthly:   deadline=2026-02-13, next=2026-03-06
    // Quarterly: deadline=2026-02-20, next=2026-05-06
    // Recurring: deadline=2026-02-20, next=2026-05-06
    // Ad-hoc:    deadline=2026-02-20, next=(none)
    // Annual:    deadline=2026-03-06, next=2027-02-06
    // Semi Annual: deadline=2026-03-06, next=2026-08-06
    function calculateDeadline(operationDate, controlFrequency) {
        const date = new Date(operationDate.getFullYear(), operationDate.getMonth(), operationDate.getDate());
        const normalized = normalizeControlFrequency(controlFrequency);

        if (!normalized) {
            return null;
        }

        switch (normalized) {
            case 'quarterly':
            case 'recurring':
            case 'ad-hoc':
                date.setDate(date.getDate() + 14);
                break;
            case 'semi annual':
            case 'annual':
                date.setMonth(date.getMonth() + 1);
                break;
            case 'monthly':
                date.setDate(date.getDate() + 7);
                break;
            default:
                return null;
        }

        return date;
    }

    function calculateNextOperationDate(operationDate, controlFrequency) {
        const date = new Date(operationDate.getFullYear(), operationDate.getMonth(), operationDate.getDate());
        const normalized = normalizeControlFrequency(controlFrequency);

        if (!normalized) {
            return null;
        }

        switch (normalized) {
            case 'monthly':
                date.setMonth(date.getMonth() + 1);
                return date;
            case 'quarterly':
            case 'recurring':
                date.setMonth(date.getMonth() + 3);
                return date;
            case 'semi annual':
                date.setMonth(date.getMonth() + 6);
                return date;
            case 'annual':
                date.setMonth(date.getMonth() + 12);
                return date;
            case 'ad-hoc':
                return null;
            default:
                return null;
        }
    }

    function applyAssignmentDatePreview(operationDate, controlFrequency) {
        const deadline = calculateDeadline(operationDate, controlFrequency);
        const nextOperationDate = calculateNextOperationDate(operationDate, controlFrequency);

        const deadlineInput = document.querySelector('input[name="controlOperationDeadline"]');
        const nextDateInput = document.querySelector('input[name="nextControlOperationDate"]');

        if (deadlineInput && deadline) {
            setDateFieldValue(deadlineInput, toIsoDate(deadline));
            console.log('Set deadline to:', deadlineInput.value);
        }
        if (nextDateInput) {
            if (nextOperationDate) {
                setDateFieldValue(nextDateInput, toIsoDate(nextOperationDate));
                console.log('Set next date to:', nextDateInput.value);
            } else if (normalizeControlFrequency(controlFrequency) === 'ad-hoc') {
                setDateFieldValue(nextDateInput, '');
                console.log('Cleared next date for ad-hoc');
            }
        }
    }



    function goBack() {
        if (document.referrer && document.referrer.includes(window.location.hostname)) {
            window.history.back();
        } else {
            window.location.href = '/controls';
        }
    }

    // ========== ПУБЛИЧНЫЙ ИНТЕРФЕЙС ==========
    return {
        toggleUserDropdown: toggleUserDropdown,
        filterUserList: filterUserList,
        toggleControlOperatorDropdown: toggleControlOperatorDropdown,
        filterControlOperatorList: filterControlOperatorList,
        toggleSoqmLeadDropdown: toggleSoqmLeadDropdown,
        filterSoqmLeadList: filterSoqmLeadList,
        toggleProcessOwnerDropdown: toggleProcessOwnerDropdown,
        filterProcessOwnerList: filterProcessOwnerList,
        toggleSharedWithDropdown: toggleSharedWithDropdown,
        filterSharedWithList: filterSharedWithList,
        removeSharedWithUser: removeSharedWithUser,
        goBack: goBack,

        init: async function() {
            console.log('View Control JS initialized');

            const searchInputs = [
                { id: 'facilitatorSearchInput', handler: filterUserList },
                { id: 'controlOperatorSearchInput', handler: filterControlOperatorList },
                { id: 'soqmLeadSearchInput', handler: filterSoqmLeadList },
                { id: 'processOwnerSearchInput', handler: filterProcessOwnerList },
                { id: 'sharedWithSearchInput', handler: filterSharedWithList }
            ];

            searchInputs.forEach(({ id, handler }) => {
                const input = document.getElementById(id);
                if (input) {
                    input.addEventListener('input', handler);
                    input.addEventListener('keyup', handler);
                }
            });

            document.addEventListener('click', function(event) {
                const dropdownContainers = document.querySelectorAll('.facilitator-dropdown');

                let isInsideAnyDropdown = false;
                dropdownContainers.forEach(container => {
                    if (container.contains(event.target)) {
                        isInsideAnyDropdown = true;
                    }
                });

                if (!isInsideAnyDropdown) {
                    if (isDropdownOpen) closeUserDropdown();
                    if (isControlOperatorDropdownOpen) closeControlOperatorDropdown();
                    if (isSoqmLeadDropdownOpen) closeSoqmLeadDropdown();
                    if (isProcessOwnerDropdownOpen) closeProcessOwnerDropdown();
                    if (isSharedWithDropdownOpen) closeSharedWithDropdown();
                }
            });

            const lastActiveTab = localStorage.getItem('lastActiveTab');
            if (lastActiveTab) {
                const tabElement = document.querySelector(`a[href="${lastActiveTab}"]`);
                if (tabElement) {
                    const tab = new bootstrap.Tab(tabElement);
                    tab.show();
                }
                localStorage.removeItem('lastActiveTab');
            }

            // LOAD DATA BEFORE INITIALIZING READONLY MODE
            const controlId = document.querySelector('input[name="id"]').value;
            await loadPermissions(controlId);
            await loadDetailsData(controlId);
            await loadDocumentsData(controlId);
            await loadUsers(); // This calls loadAssignmentData internally

            initializeReadOnlyMode();
            normalizeAssignmentDateFieldsForDisplay();

            const editBtn = document.getElementById('editBtn');
            const renameIdBtn = document.getElementById('renameIdBtn');
            const changelogBtn = document.getElementById('changelogBtn');
            const saveEditBtn = document.getElementById('saveEditBtn');
            const cancelEditBtn = document.getElementById('cancelEditBtn');

            if (!changelogBtn) {
                console.error('Changelog button not found');
                return;
            }

            if (editBtn) {
                editBtn.addEventListener('click', switchToEditMode);
            }

            if (saveEditBtn) {
                saveEditBtn.addEventListener('click', async function(event) {
                    event.preventDefault();

                    console.log('=== SAVE BUTTON CLICKED ===');

                    if (!validateControlSaveRequiredFields()) {
                        return;
                    }

                    saveEditBtn.disabled = true;
                    const originalText = saveEditBtn.textContent;
                    saveEditBtn.textContent = 'Saving...';

                    try {
                        const result = await saveControlChanges();
                        console.log('✅ Save successful:', result);

                        saveEditBtn.disabled = false;
                        saveEditBtn.textContent = originalText;

                    } catch (error) {
                        console.error('❌ Save error in button handler:', error);

                        saveEditBtn.disabled = false;
                        saveEditBtn.textContent = originalText;

                        let errorMessage = error.message;

                        if (errorMessage.includes('Validation failed') || errorMessage.includes('invalid')) {
                            alert('Validation error: ' + errorMessage);
                        } else if (errorMessage.includes('failed')) {
                            const match = errorMessage.match(/Status (\d+): (.*)/);
                            if (match) {
                                alert(`Server error (${match[1]}): ${match[2]}`);
                            } else {
                                alert('Error saving changes: ' + errorMessage);
                            }
                        } else {
                            alert('Error: ' + errorMessage);
                        }
                    }
                });
            }

            if (cancelEditBtn) {
                cancelEditBtn.addEventListener('click', function() {
                    // Regression check:
                    // Cancel must only rollback UI state to the pre-edit snapshot (no save/network calls).
                    switchToReadOnlyMode();
                });
            }

            const handleOperationDateChange = function() {
                console.log('Control Operation Date changed:', this.value);

                const isoValue = formatDateForApi(this.value || this.dataset.isoValue || '');
                if (isoValue) {
                    const operationDate = parseIsoDate(isoValue);
                    if (!operationDate) {
                        return;
                    }
                    const controlFrequency = document.querySelector('#controlForm [name="controlFrequency"]')?.value
                        || document.querySelector('[name="controlFrequency"]')?.value;
                    console.log('Control Frequency:', controlFrequency);
                    applyAssignmentDatePreview(operationDate, controlFrequency);
                }
            };

            const operationDateInput = document.querySelector('input[name="controlOperationDate"]');
            if (operationDateInput) {
                operationDateInput.addEventListener('change', handleOperationDateChange);
                operationDateInput.addEventListener('input', handleOperationDateChange);
            }

            document.querySelector('#controlForm select[name="controlFrequency"]')?.addEventListener('change', function() {
                console.log('Control Frequency changed:', this.value);
                updateCalculatedDates();
            });

            if (renameIdBtn) {
                renameIdBtn.addEventListener('click', function() {
                console.log('📝 Opening Rename ID modal');

                const modalElement = document.getElementById('renameIdModal');
                const newControlIdInput = document.getElementById('newControlId');
                const controlIdError = document.getElementById('controlIdError');
                const confirmRenameBtn = document.getElementById('confirmRenameBtn');
                const currentControlId = document.getElementById('controlId').value;

                const originalControlId = currentControlId.trim();

                newControlIdInput.value = currentControlId;
                controlIdError.style.display = 'none';
                controlIdError.textContent = '';

                confirmRenameBtn.disabled = true;
                confirmRenameBtn.classList.add('btn-disabled');

                function checkIfValueChanged() {
                    const currentValue = newControlIdInput.value;
                    const trimmedCurrent = currentValue.trim();

                    if (trimmedCurrent === '') {
                        confirmRenameBtn.disabled = true;
                        confirmRenameBtn.classList.add('btn-disabled');
                        return;
                    }

                    const isChanged = (originalControlId !== trimmedCurrent);

                    if (isChanged) {
                        confirmRenameBtn.disabled = false;
                        confirmRenameBtn.classList.remove('btn-disabled');
                    } else {
                        confirmRenameBtn.disabled = true;
                        confirmRenameBtn.classList.add('btn-disabled');
                    }
                }

                const existingModal = bootstrap.Modal.getInstance(modalElement);
                if (existingModal) {
                    existingModal.hide();
                }
                const renameModal = new bootstrap.Modal(modalElement, {
                    backdrop: true,
                    keyboard: true,
                    focus: true
                });

                newControlIdInput.addEventListener('input', checkIfValueChanged);
                newControlIdInput.addEventListener('keyup', checkIfValueChanged);
                newControlIdInput.addEventListener('change', checkIfValueChanged);
                newControlIdInput.addEventListener('paste', function() {
                    setTimeout(checkIfValueChanged, 10);
                });

                setTimeout(checkIfValueChanged, 0);

                document.getElementById('confirmRenameBtn').onclick = async function() {
                    if (this.disabled) return;

                    const newControlId = document.getElementById('newControlId').value.trim();
                    const controlIdError = document.getElementById('controlIdError');

                    controlIdError.style.display = 'none';
                    controlIdError.textContent = '';

                    if (newControlId === originalControlId) {
                        renameModal.hide();
                        return;
                    }

                    const renameBtn = this;
                    renameBtn.disabled = true;
                    renameBtn.textContent = 'Checking...';

                    try {
                        const isUnique = await checkControlIdUnique(newControlId, currentControlId);

                        if (!isUnique) {
                            throw new Error('Control ID already exists. Please choose a different ID.');
                        }

                        const updatedControl = await renameControlId(newControlId);

                        document.title = 'Control - ' + updatedControl.controlId;

                        document.querySelectorAll('.control-id-display').forEach(element => {
                            element.textContent = updatedControl.controlId;
                            if (element.tagName === 'INPUT') {
                                element.value = updatedControl.controlId;
                            }
                        });

                        modalElement.addEventListener('hidden.bs.modal', function handleRenameHidden() {
                            showAppModal({
                                variant: 'success',
                                title: 'Renamed Successfully',
                                message: 'Control ID has been renamed to ' + updatedControl.controlId,
                                redirectUrl: '/'
                            });
                            modalElement.removeEventListener('hidden.bs.modal', handleRenameHidden);
                        });
                        renameModal.hide();

                    } catch (error) {
                        console.error('Error renaming control ID:', error);
                        controlIdError.textContent = error.message;
                        controlIdError.style.display = 'block';

                        renameBtn.disabled = false;
                        renameBtn.textContent = 'Rename';
                        checkIfValueChanged();
                    }
                };

                const cancelBtn = modalElement.querySelector('.btn-secondary[data-bs-dismiss="modal"]');
                if (cancelBtn) {
                    cancelBtn.onclick = function() {
                        renameModal.hide();
                    };
                }

                const closeBtn = modalElement.querySelector('.btn-close[data-bs-dismiss="modal"]');
                if (closeBtn) {
                    closeBtn.onclick = function() {
                        renameModal.hide();
                    };
                }

                modalElement.addEventListener('hidden.bs.modal', function cleanup() {
                    newControlIdInput.removeEventListener('input', checkIfValueChanged);
                    newControlIdInput.removeEventListener('keyup', checkIfValueChanged);
                    newControlIdInput.removeEventListener('change', checkIfValueChanged);

                    confirmRenameBtn.disabled = false;
                    confirmRenameBtn.classList.remove('btn-disabled');
                    confirmRenameBtn.textContent = 'Rename';

                    modalElement.removeEventListener('hidden.bs.modal', cleanup);
                });

                renameModal.show();
            });
            }

            changelogBtn.addEventListener('click', function() {
                const controlId = document.querySelector('input[name="id"]')?.value;
                if (!controlId) {
                    alert('Control ID not found');
                    return;
                }

                const modalElement = document.getElementById('changelogModal');
                if (!modalElement) {
                    alert('Changelog modal not found');
                    return;
                }

                const changelogModal = new bootstrap.Modal(modalElement);
                changelogModal.show();
                loadChangelog(controlId);
            });

            // ========== WORKFLOW INITIALIZATION ==========
            console.log('=== WORKFLOW INITIALIZATION ===');

            // Получаем значения из скрытых полей
            const userRoleElement = document.getElementById('currentUserRole');
            const statusElement = document.getElementById('currentPerformanceStatus');
            const controlIdElement = document.querySelector('input[name="id"]');

            console.log('User Role value:', userRoleElement?.value);
            console.log('Status value:', statusElement?.value);
            console.log('Control ID value:', controlIdElement?.value);

            // Проверяем workflow контейнер
            const workflowContainer = document.getElementById('workflow-buttons-container');
            console.log('Workflow container exists:', !!workflowContainer);

            if (workflowContainer) {
                const buttons = workflowContainer.querySelectorAll('.workflow-btn');
                console.log(`Found ${buttons.length} workflow buttons`);
            }

            // Инициализируем workflow кнопки если есть данные
            const userRole = userRoleElement?.value;
            const performanceStatus = statusElement?.value;
            const controlIdValue = controlIdElement?.value;

            if (userRole && performanceStatus && controlIdValue) {
                console.log('🔄 Initializing workflow buttons...');
                console.log(`   User Role: "${userRole}"`);
                console.log(`   Status: "${performanceStatus}"`);
                console.log(`   Control ID: "${controlIdValue}"`);

                // ★★★★ ВЫЗОВ ФУНКЦИИ ПОКАЗА КНОПОК ★★★★
                showWorkflowButtonsByStatusAndRole(performanceStatus, userRole);

                // Добавляем обработчики кликов
                document.querySelectorAll('.workflow-btn').forEach(btn => {
                    btn.addEventListener('click', handleWorkflowButtonClick);
                });

                console.log('✅ Workflow buttons initialized');
            } else {
                console.warn('⚠️ Cannot init workflow buttons: missing data');
            }

            // Добавляем обработчик для кнопки Confirm в модалке workflow
            const confirmWorkflowBtn = document.getElementById('confirmWorkflowAction');
            if (confirmWorkflowBtn) {
                confirmWorkflowBtn.addEventListener('click', confirmWorkflowAction);
                console.log('✅ Confirm workflow button handler added');
            } else {
                console.warn('⚠️ Confirm workflow button not found');
            }

            // Добавляем обработчик для подтверждения Submit to Control Operator
            const confirmSubmitBtn = document.getElementById('confirmSubmitBtn');
            if (confirmSubmitBtn) {
                confirmSubmitBtn.addEventListener('click', confirmSubmitWorkflowAction);
                console.log('✅ Confirm submit button handler added');
            } else {
                console.warn('⚠️ Confirm submit button not found');
            }

            // ========== АВТОМАТИЧЕСКИЙ ПОКАЗ КНОПОК С ЦВЕТНЫМИ РАМКАМИ ==========
            console.log('=== AUTOMATIC BUTTON VISIBILITY ===');

            // Даем время загрузиться всему контенту
            setTimeout(() => {
                const currentUserRole = document.getElementById('currentUserRole')?.value;
                const currentStatus = document.getElementById('currentPerformanceStatus')?.value;
                const workflowStatusInput = document.querySelector('input[name="performanceStatus"]');
                const workflowStatus = workflowStatusInput ? workflowStatusInput.value : '';
                const isFacilitatorFlag = document.getElementById('isFacilitator')?.value === 'true';
                const isControlOperatorFlag = document.getElementById('isControlOperator')?.value === 'true';
                const isSoqmLeadFlag = document.getElementById('isSoqmLead')?.value === 'true';
                const isProcessOwnerFlag = document.getElementById('isProcessOwner')?.value === 'true';

                console.log('Final check - Role:', currentUserRole, 'Performance Status:', currentStatus, 'Workflow Status:', workflowStatus);
                console.log('Is Facilitator for this control:', isFacilitatorFlag);
                console.log('Is Control Operator for this control:', isControlOperatorFlag);
                console.log('Is SoQM Lead for this control:', isSoqmLeadFlag);
                console.log('Is Process Owner for this control:', isProcessOwnerFlag);

                if (currentUserRole === 'SOQM_LEAD') {
                    console.log('✅ SoQM Lead role override - editing enabled for all statuses');
                    return;
                }

                // Only lock form if control is actually in workflow
                if (workflowStatus && workflowStatus !== '' && workflowStatus !== 'DRAFT') {
                    if (workflowStatus === 'IN_PROGRESS') {
                        // Control is in IN_PROGRESS
                        if (isFacilitatorFlag) {
                            // Show Submit to Control Operator button for assigned Facilitator
                            const submitToOperatorBtn = document.getElementById('submitForReviewBtn');
                            if (submitToOperatorBtn) {
                                submitToOperatorBtn.style.display = 'inline-block';
                                console.log('✅ Button "Submit to Control Operator" shown for assigned Facilitator!');
                            }
                            // Facilitator can edit during IN_PROGRESS
                            console.log('✅ Assigned Facilitator can edit control in IN_PROGRESS');
                        } else {
                            // For non-Facilitators, lock the control
                            console.log('🔒 Control in IN_PROGRESS - locking for non-Facilitator');
                            lockControlForm();
                        }
                    } else if (workflowStatus === 'REVIEW') {
                        // Control is in REVIEW
                        if (isControlOperatorFlag) {
                            // Control Operator can edit during REVIEW
                            console.log('✅ Assigned Control Operator can edit control in REVIEW');
                        } else {
                            // For non-Control Operators, lock the control
                            console.log('🔒 Control in REVIEW - locking for non-Control Operator');
                            lockControlForm();
                        }
                    } else if (workflowStatus === 'SOQM_HEAD_REVIEW') {
                        // Control is in SOQM_HEAD_REVIEW
                        if (isSoqmLeadFlag) {
                            // SoQM Lead can edit during SOQM_HEAD_REVIEW
                            console.log('✅ Assigned SoQM Lead can edit control in SOQM_HEAD_REVIEW');
                        } else {
                            // For non-SoQM Leads, lock the control
                            console.log('🔒 Control in SOQM_HEAD_REVIEW - locking for non-SoQM Lead');
                            lockControlForm();
                        }
                    } else if (workflowStatus === 'PROCESS_OWNER_REVIEW') {
                        // Control is in PROCESS_OWNER_REVIEW
                        if (isProcessOwnerFlag) {
                            // Process Owner can edit during PROCESS_OWNER_REVIEW
                            console.log('✅ Assigned Process Owner can edit control in PROCESS_OWNER_REVIEW');
                        } else {
                            // For non-Process Owners, lock the control
                            console.log('🔒 Control in PROCESS_OWNER_REVIEW - locking for non-Process Owner');
                            lockControlForm();
                        }
                    } else if (workflowStatus === 'COMPLETED') {
                        // Check if user is shared viewer — allow field-level edit
                        const isSharedViewerFlag = document.getElementById('isSharedViewer')?.value === 'true';
                        if (isSharedViewerFlag) {
                            console.log('✅ Shared viewer on COMPLETED control - field-level edit via permissions');
                            // Don't lock — Edit button stays visible, permissions will restrict to specific fields
                        } else {
                            console.log('🔒 Control COMPLETED - locking form');
                            lockControlForm();
                        }
                    } else {
                        // Control is in other workflow status - lock it
                        console.log('🔒 Control in workflow - locking form');
                        lockControlForm();
                    }
                } else {
                    // Control not in workflow - Edit button should be visible
                    console.log('✅ Control not in workflow - Edit button available');
                }

                // Show workflow container if needed
                const container = document.getElementById('workflow-buttons-container');
                if (container) {
                    container.style.display = 'inline-flex';
                    console.log('✅ Контейнер показан!');
                }
            }, 1500); // Ждем 1.5 секунды чтобы все загрузилось

            console.log('=== INIT COMPLETE ===');
        }

    };
})();

function buildDetailsPayload(controlId) {
    const detailsForm = document.getElementById('detailsForm');
    if (!detailsForm) {
        return null;
    }

    const getDetailsValue = (selector) => {
        const element = detailsForm.querySelector(selector);
        return element ? element.value : '';
    };

    const payload = {
        controlId: parseInt(controlId, 10),
        processName: getDetailsValue('[name="processName"]'),
        homogeneity: getDetailsValue('[name="homogeneity"]'),
        referencesToControl: getDetailsValue('[name="referencesToControl"]'),
        department: getDetailsValue('[name="department"]'),
        processActivities: getDetailsValue('[name="processActivities"]'),
        otherRelatedControls: getDetailsValue('[name="otherRelatedControls"]'),
        itApplications: getDetailsValue('[name="itApplications"]'),
        controlStepsPerformed: getDetailsValue('[name="controlStepsPerformed"]'),
        soqmHeadComments: getDetailsValue('[name="soqmHeadComments"]'),
        processOwnerComments: getDetailsValue('[name="processOwnerComments"]')
    };

    return applyDetailsPermissions(payload);
}

function applyDetailsPermissions(payload) {
    const permissions = window.qtrackerPermissions || {};
    if (permissions.canEditAll) {
        return payload;
    }

    const allowed = new Set();
    if (permissions.canEditStepsPerformed) {
        allowed.add('controlStepsPerformed');
    }
    if (permissions.canEditProcessOwnerComments) {
        allowed.add('processOwnerComments');
    }

    if (!detailsDataCache) {
        return payload;
    }

    const merged = { ...payload };
    Object.keys(merged).forEach(key => {
        if (key === 'controlId') {
            return;
        }
        if (!allowed.has(key) && detailsDataCache[key] !== undefined && detailsDataCache[key] !== null) {
            merged[key] = detailsDataCache[key];
        }
    });

    return merged;
}

function lockControlForm() {
    console.log('🔒 Locking control form...');

    // Disable all form inputs in Assignment tab
    const assignmentInputs = document.querySelectorAll('[id^="facilitator"], [id^="controlOperator"], [id^="soqmLead"], [id^="processOwner"]');
    assignmentInputs.forEach(input => {
        input.disabled = true;
    });

    // Hide edit button
    const editBtn = document.getElementById('editBtn');
    if (editBtn) {
        editBtn.style.display = 'none';
    }

    // Hide dropdown buttons
    const dropdownButtons = document.querySelectorAll('[data-role="FACILITATOR"]');
    dropdownButtons.forEach(btn => {
        btn.disabled = true;
        btn.style.opacity = '0.5';
    });

    console.log('✅ Form locked');
}

// ========== SUBMIT TO CONTROL OPERATOR ==========
function showSavedSuccessfullyModal(options) {
    const redirectUrl = options ? options.redirectUrl : null;
    const timerMs = options ? options.timerMs : null;

    showAppModal({
        variant: 'success',
        title: 'Saved Successfully',
        message: 'All control data has been saved',
        redirectUrl: redirectUrl,
        autoCloseMs: timerMs === null || timerMs === undefined ? undefined : timerMs
    });
}

function showWorkflowRequirementMessage(message, field) {
    if (window.showAppModal) {
        showAppModal({
            variant: 'warning',
            title: 'Missing Required Field',
            message: message,
            autoCloseMs: 0
        });
    } else {
        alert(message);
    }
    if (field && typeof field.focus === 'function') {
        field.focus();
    }
}

function isBlankValueForWorkflow(value) {
    return value === null || value === undefined || String(value).trim() === '';
}

function getWorkflowRoleRequirement() {
    const isFacilitator = document.getElementById('isFacilitator')?.value === 'true';
    const isControlOperator = document.getElementById('isControlOperator')?.value === 'true';
    const isSoqmLead = document.getElementById('isSoqmLead')?.value === 'true';
    const isProcessOwner = document.getElementById('isProcessOwner')?.value === 'true';
    const performanceStatus = document.querySelector('input[name="performanceStatus"]')?.value || '';

    if (isFacilitator && performanceStatus === 'IN_PROGRESS') {
        return {
            field: document.querySelector('textarea[name="controlStepsPerformed"]'),
            message: 'To submit, please fill: Control steps performed and results'
        };
    }

    if (isControlOperator && performanceStatus === 'REVIEW') {
        return {
            field: document.querySelector('textarea[name="controlStepsPerformed"]'),
            message: 'To submit, please fill: Control steps performed and results'
        };
    }

    if (isSoqmLead && performanceStatus === 'SOQM_HEAD_REVIEW') {
        return {
            field: document.querySelector('textarea[name="controlStepsPerformed"]'),
            message: 'To submit, please fill: Control steps performed and results'
        };
    }

    return null;
}

function validateWorkflowRoleRequirement() {
    const requirement = getWorkflowRoleRequirement();
    if (!requirement) {
        return true;
    }

    const field = requirement.field;
    if (!field || isBlankValueForWorkflow(field.value)) {
        if (field) {
            field.classList.add('field-required-error');
        }
        showWorkflowRequirementMessage(requirement.message, field);
        return false;
    }

    field.classList.remove('field-required-error');
    return true;
}

function saveDetailsDataSilently(controlId) {
    const detailsData = buildDetailsPayload(controlId);
    if (!detailsData) {
        return Promise.resolve({ success: true, skipped: true });
    }

    return fetch('/api/control-details', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(detailsData)
    })
    .then(async response => {
        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || 'Details save failed');
        }
        detailsDataCache = { ...(detailsDataCache || {}), ...detailsData };
        return { success: true };
    });
}

async function ensureWorkflowRoleReady() {
    if (!validateWorkflowRoleRequirement()) {
        return false;
    }

    const requirement = getWorkflowRoleRequirement();
    if (!requirement) {
        return true;
    }

    const controlId = document.querySelector('input[name="id"]')?.value;
    if (!controlId) {
        return true;
    }

    try {
        await saveDetailsDataSilently(controlId);
        return true;
    } catch (error) {
        showWorkflowRequirementMessage(
            error.message || 'Failed to save required field before submit.',
            requirement.field
        );
        return false;
    }
}

let currentSubmitAction = null;

function submitWorkflowActionWithModal(options) {
    const {
        url,
        confirmBtnId,
        confirmModalId,
        successRedirectUrl,
        successLogMessage,
        successTimerMs
    } = options;

    const confirmBtn = document.getElementById(confirmBtnId);
    const originalBtnText = confirmBtn ? confirmBtn.innerHTML : '';

    if (confirmBtn) {
        confirmBtn.disabled = true;
        confirmBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Submitting...';
    }

    return fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(async response => {
        const responseText = await response.text();
        let data = null;
        if (responseText) {
            try {
                data = JSON.parse(responseText);
            } catch (e) {
                data = { message: responseText };
            }
        }

        if (!response.ok) {
            const message = (data && data.message) ? data.message : 'Failed to submit control';
            throw new Error(message);
        }

        return data || { success: true };
    })
    .then(data => {
        if (confirmModalId) {
            const modal = bootstrap.Modal.getInstance(document.getElementById(confirmModalId));
            if (modal) modal.hide();
        }

        if (successLogMessage) {
            console.log(successLogMessage);
        }

        showSavedSuccessfullyModal({
            redirectUrl: successRedirectUrl,
            timerMs: successTimerMs
        });
        return data;
    })
    .catch(error => {
        console.error('❌ Error submitting control:', error);

        showAppModal({
            variant: 'error',
            title: 'Submission Failed',
            message: error.message || 'Failed to submit control. Please try again.',
            autoCloseMs: 0
        });

        if (confirmBtn) {
            confirmBtn.disabled = false;
            confirmBtn.innerHTML = originalBtnText || 'Confirm Submission';
        }

        throw error;
    });
}

function openSubmitConfirmationModal(actionKey) {
    currentSubmitAction = actionKey;

    const modalElement = document.getElementById('submitConfirmationModal');
    if (!modalElement) {
        return;
    }

    const titleElement = modalElement.querySelector('.modal-title');
    const messageElement = modalElement.querySelector('.modal-body p');

    if (actionKey === 'SUBMIT_TO_PROCESS_OWNER') {
        if (titleElement) {
            titleElement.textContent = 'Submit Control to Process Owner';
        }
        if (messageElement) {
            messageElement.innerHTML = 'Are you sure you want to submit this control to the <strong>Process Owner</strong> for review?';
        }
    } else {
        if (titleElement) {
            titleElement.textContent = 'Submit Control to Control Operator';
        }
        if (messageElement) {
            messageElement.innerHTML = 'Are you sure you want to submit this control to the <strong>Control Operator</strong> for review?';
        }
    }

    const modal = new bootstrap.Modal(modalElement);
    modal.show();
}

// Robust workflow handler binding (event delegation)
document.addEventListener('click', async (event) => {
    const reviewBtn = event.target.closest('#submitForReviewBtn');
    const processOwnerBtn = event.target.closest('#submitToProcessOwnerBtn');
    const returnToFacilitatorBtn = event.target.closest('#returnToFacilitatorBtn');
    const submitToSoqmLeadBtn = event.target.closest('#submitToSoqmLeadBtn');
    const returnToOperatorBtn = event.target.closest('#returnToOperatorBtn');
    const returnToSoqmLeadBtn = event.target.closest('#returnToSoqmLeadBtn');
    const sharedSubmitToSoqmBtn = event.target.closest('#sharedSubmitToSoqmBtn');
    if (!reviewBtn && !processOwnerBtn && !returnToFacilitatorBtn && !submitToSoqmLeadBtn && !returnToOperatorBtn && !returnToSoqmLeadBtn && !sharedSubmitToSoqmBtn) {
        return;
    }

    event.preventDefault();
    event.stopPropagation();

    if (reviewBtn) {
        console.log('Submit for Review clicked');
        if (!await ensureWorkflowRoleReady()) {
            return;
        }
        openSubmitConfirmationModal('SUBMIT_TO_OPERATOR');
        return;
    }

    if (processOwnerBtn) {
        if (!await ensureWorkflowRoleReady()) {
            return;
        }
        openSubmitConfirmationModal('SUBMIT_TO_PROCESS_OWNER');
        return;
    }

    if (returnToFacilitatorBtn) {
        console.log('Return to Facilitator clicked');
        if (!await ensureWorkflowRoleReady()) {
            return;
        }
        const modalElement = document.getElementById('returnFacilitatorModal');
        if (modalElement) {
            const modal = new bootstrap.Modal(modalElement);
            modal.show();
        }
        return;
    }

    if (submitToSoqmLeadBtn) {
        console.log('Submit for SoQM Team Review clicked');
        if (!await ensureWorkflowRoleReady()) {
            return;
        }
        const modalElement = document.getElementById('submitSoqmLeadModal');
        if (modalElement) {
            const modal = new bootstrap.Modal(modalElement);
            modal.show();
        }
        return;
    }

    if (returnToOperatorBtn) {
        console.log('Return to Operator clicked');
        if (!await ensureWorkflowRoleReady()) {
            return;
        }
        const modalElement = document.getElementById('returnOperatorModal');
        if (modalElement) {
            const modal = new bootstrap.Modal(modalElement);
            modal.show();
        }
        return;
    }

    if (returnToSoqmLeadBtn) {
        console.log('Return to SoQM Lead clicked');
        if (!await ensureWorkflowRoleReady()) {
            return;
        }
        const modalElement = document.getElementById('returnSoqmLeadModal');
        if (modalElement) {
            const modal = new bootstrap.Modal(modalElement);
            modal.show();
        }
    }

    if (sharedSubmitToSoqmBtn) {
        console.log('Shared Submit for SoQM Team clicked');
        if (!await ensureWorkflowRoleReady()) {
            return;
        }
        const modalElement = document.getElementById('sharedSubmitSoqmModal');
        if (modalElement) {
            const modal = new bootstrap.Modal(modalElement);
            modal.show();
        }
    }
});

document.addEventListener('submit', (event) => {
    const form = event.target;
    if (!form) {
        return;
    }

    if (form.querySelector('#submitForReviewBtn, #submitToProcessOwnerBtn, #submitToSoqmLeadBtn, #returnToFacilitatorBtn, #returnToOperatorBtn, #returnToSoqmLeadBtn, #sharedSubmitToSoqmBtn')) {
        event.preventDefault();
    }
});

console.log('Workflow handlers bound');

async function confirmSubmitWorkflowAction() {
    if (!await ensureWorkflowRoleReady()) {
        return;
    }

    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;

    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    if (!currentSubmitAction) {
        alert('Error: Submission type not selected');
        return;
    }

    const actionConfig = {
        SUBMIT_TO_OPERATOR: {
            url: '/api/workflow/submit-to-control-operator?controlId=' + controlId,
            successLogMessage: 'Submit for Review success -> showing modal',
            successTimerMs: 2500
        },
        SUBMIT_TO_PROCESS_OWNER: {
            url: '/api/workflow/submit-to-process-owner?controlId=' + controlId,
            successTimerMs: 2500
        }
    };

    const config = actionConfig[currentSubmitAction];
    if (!config) {
        alert('Error: Unsupported submission type');
        return;
    }

    submitWorkflowActionWithModal({
        url: config.url,
        confirmBtnId: 'confirmSubmitBtn',
        confirmModalId: 'submitConfirmationModal',
        successRedirectUrl: '/',
        successLogMessage: config.successLogMessage,
        successTimerMs: config.successTimerMs
    });
}

// ========== WORKFLOW BUTTON HANDLERS ==========

async function confirmSubmitToSoqmLead() {
    console.log('🔘 Confirm Submit to SoQM Lead');

    if (!await ensureWorkflowRoleReady()) {
        return;
    }
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    submitWorkflowActionWithModal({
        url: '/api/workflow/submit-to-soqm-lead?controlId=' + controlId,
        confirmBtnId: 'confirmSubmitSoqmLeadBtn',
        confirmModalId: 'submitSoqmLeadModal',
        successRedirectUrl: '/',
        successLogMessage: 'Submit for SoQM Team Review success -> showing popup',
        successTimerMs: 2500
    });
}

// ========== SHARED SUBMIT TO SOQM LEAD (Shared viewer → SoQM Lead from COMPLETED) ==========
async function confirmSharedSubmitToSoqmLead() {
    console.log('🔘 Confirm Shared Submit to SoQM Lead');

    if (!await ensureWorkflowRoleReady()) {
        return;
    }

    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;

    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    submitWorkflowActionWithModal({
        url: '/api/workflow/shared-submit-to-soqm-lead?controlId=' + controlId,
        confirmBtnId: 'confirmSharedSubmitSoqmBtn',
        confirmModalId: 'sharedSubmitSoqmModal',
        successRedirectUrl: '/',
        successLogMessage: 'Shared Submit for SoQM Team success -> showing popup',
        successTimerMs: 2500
    });
}

// ========== RETURN TO FACILITATOR HANDLERS (Control Operator → Facilitator) ==========
async function confirmReturnToFacilitator() {
    console.log('🔘 Confirm Return to Facilitator');

    if (!await ensureWorkflowRoleReady()) {
        return;
    }
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    // Get optional comments
    const commentsElement = document.getElementById('returnComments');
    const comments = commentsElement ? commentsElement.value.trim() : '';

    // Build request URL with optional comments parameter
    let url = '/api/workflow/return-to-facilitator?controlId=' + controlId;
    if (comments) {
        url += '&comments=' + encodeURIComponent(comments);
    }

    submitWorkflowActionWithModal({
        url: url,
        confirmBtnId: 'confirmReturnFacilitatorBtn',
        confirmModalId: 'returnFacilitatorModal',
        successRedirectUrl: '/controls',
        successLogMessage: 'Return to Facilitator success -> showing popup',
        successTimerMs: 2500
    });
}

// ========== WORKFLOW BUTTON HANDLERS ==========
function handleWorkflowButtonClick(event) {
    event.preventDefault();
    
    const btn = event.currentTarget;
    const action = btn.dataset.action;
    const status = btn.dataset.status;
    const requiresComment = btn.dataset.requiresComment === 'true';
    
    console.log('Workflow button clicked:', {
        action: action,
        status: status,
        requiresComment: requiresComment
    });

    currentWorkflowAction = action;
    currentWorkflowRequiresComment = requiresComment;

    // Show confirmation modal
    const modal = document.getElementById('workflowActionModal');
    if (modal) {
        const messageElement = document.getElementById('workflowActionMessage');
        messageElement.textContent = btn.textContent + ' - Are you sure?';

        const commentSection = document.getElementById('workflowCommentSection');
        if (commentSection) {
            commentSection.style.display = requiresComment ? 'block' : 'none';
        }

        const modalInstance = new bootstrap.Modal(modal);
        modalInstance.show();
    }
}

function initChangelogFilters() {
    if (changelogFilterInitialized) {
        return;
    }
    const toolbar = document.getElementById('changelogToolbar');
    if (!toolbar) {
        return;
    }
    const buttons = toolbar.querySelectorAll('[data-changelog-filter]');
    if (!buttons.length) {
        return;
    }
    buttons.forEach(button => {
        button.addEventListener('click', () => {
            setChangelogFilter(button.dataset.changelogFilter);
        });
    });
    changelogFilterInitialized = true;
}

function setChangelogFilter(filter) {
    changelogFilter = filter || 'all';
    updateChangelogFilterButtons(changelogFilter);
    renderChangelog(changelogEntries, changelogFilter);
}

function updateChangelogFilterButtons(activeFilter) {
    const toolbar = document.getElementById('changelogToolbar');
    if (!toolbar) {
        return;
    }
    const buttons = toolbar.querySelectorAll('[data-changelog-filter]');
    buttons.forEach(button => {
        const isActive = button.dataset.changelogFilter === activeFilter;
        button.classList.toggle('active', isActive);
    });
}

function updateChangelogCount(visible, total) {
    const count = document.getElementById('changelogCount');
    if (!count) {
        return;
    }
    if (!total) {
        count.textContent = '';
        return;
    }
    const label = changelogFilter === 'workflow'
        ? 'workflow entries'
        : changelogFilter === 'field'
            ? 'field changes'
            : 'entries';
    count.textContent = `${visible} of ${total} ${label}`;
}

function getChangelogEntryType(entry) {
    const name = (entry.eventName || '').toLowerCase();
    const hasFieldChanges = Array.isArray(entry.fieldChanges) && entry.fieldChanges.length > 0;
    if (name.includes('control performance') || name.includes('review comments') || name.includes('workflow')) {
        return 'workflow';
    }
    if (hasFieldChanges || entry.eventName === 'New Control' || entry.eventName === 'Edit Control') {
        return 'field';
    }
    return 'field';
}

function getChangelogBadge(entry, entryType) {
    if (entryType === 'workflow') {
        return { label: getWorkflowBadgeLabel(entry.eventName), className: 'badge badge-changelog-workflow', markerClass: 'workflow' };
    }
    if (entry.eventName === 'New Control') {
        return { label: 'Created', className: 'badge badge-changelog-created', markerClass: '' };
    }
    return { label: 'Updated', className: 'badge badge-changelog-updated', markerClass: 'updated' };
}

function getWorkflowBadgeLabel(eventName) {
    const name = (eventName || '').toLowerCase();
    if (name.includes('submitted')) {
        return 'Workflow: Submit';
    }
    if (name.includes('returned')) {
        return 'Workflow: Return';
    }
    if (name.includes('completed') || name.includes('approve')) {
        return 'Workflow: Complete';
    }
    if (name.includes('initiated')) {
        return 'Workflow: Initiate';
    }
    if (name.includes('comment')) {
        return 'Workflow: Comment';
    }
    return 'Workflow';
}

function formatChangelogDate(entry) {
    if (window.QTrackerDate && entry.createdAt) {
        return window.QTrackerDate.formatDisplayDateTimeFromIso(entry.createdAt);
    }
    return entry.formattedTime || '-';
}

function normalizeChangelogValue(value) {
    if (value === null || value === undefined) {
        return '';
    }
    return String(value).trim();
}

function shouldShowChange(oldValue, newValue) {
    const oldNorm = normalizeChangelogValue(oldValue);
    const newNorm = normalizeChangelogValue(newValue);
    if (!oldNorm && !newNorm) {
        return false;
    }
    if (oldNorm === newNorm) {
        return false;
    }
    return true;
}

function buildFieldChanges(entry) {
    const tableType = (entry.tableType || '').toUpperCase();
    const isSingle = tableType === 'SINGLE' || entry.eventName === 'New Control';
    const rawChanges = Array.isArray(entry.fieldChanges) ? entry.fieldChanges : [];
    const changes = [];
    rawChanges.forEach(change => {
        const field = normalizeChangelogValue(change.field);
        const oldVal = normalizeChangelogValue(change.oldValue);
        const newVal = normalizeChangelogValue(change.newValue);

        if (isSingle) {
            const value = newVal || oldVal;
            if (value) {
                changes.push({ field: field, value: value });
            }
        } else if (shouldShowChange(oldVal, newVal)) {
            changes.push({ field: field, oldValue: oldVal, newValue: newVal });
        }
    });

    return { isSingle: isSingle, changes: changes };
}

function inferWorkflowTransition(actionName) {
    const label = (actionName || '').toLowerCase();
    if (label.includes('initiated')) {
        return { from: 'DRAFT', to: 'IN_PROGRESS' };
    }
    if (label.includes('submitted for review')) {
        return { from: 'IN_PROGRESS', to: 'REVIEW' };
    }
    if (label.includes('submitted for soqm')) {
        return { from: 'REVIEW', to: 'SOQM_HEAD_REVIEW' };
    }
    if (label.includes('submitted to process owner')) {
        return { from: 'SOQM_HEAD_REVIEW', to: 'PROCESS_OWNER_REVIEW' };
    }
    if (label.includes('completed')) {
        return { from: 'PROCESS_OWNER_REVIEW', to: 'COMPLETED' };
    }
    if (label.includes('returned')) {
        return { from: 'REVIEW', to: 'IN_PROGRESS' };
    }
    return { from: '', to: '' };
}

function buildWorkflowSummary(eventName, transition) {
    const name = (eventName || '').toLowerCase();
    const fromStep = transition && transition.from ? transition.from : '';
    const toStep = transition && transition.to ? transition.to : '';

    if (name.includes('initiated')) {
        return 'Control initiated and status In Progress';
    }
    if (name.includes('submitted for review')) {
        return 'Control submitted for review';
    }
    if (name.includes('submitted for soqm')) {
        return 'Control submitted for SoQM review';
    }
    if (name.includes('submitted to process owner')) {
        return 'Control submitted to Process Owner';
    }
    if (name.includes('completed')) {
        return 'Control completed';
    }
    if (name.includes('returned')) {
        return 'Control returned for rework';
    }
    if (name.includes('comment')) {
        return 'Workflow comment added';
    }
    if (fromStep && toStep) {
        return `Status changed from ${fromStep} to ${toStep}`;
    }
    return 'Workflow update';
}

function shouldShowWorkflowComment(entry) {
    const details = normalizeChangelogValue(entry.eventDetails);
    if (!details) {
        return false;
    }
    const lower = details.toLowerCase();
    if (lower.includes('workflow initiated by facilitator')) {
        return false;
    }
    return true;
}

function buildChangelogCard(entry) {
    const entryType = getChangelogEntryType(entry);
    const badge = getChangelogBadge(entry, entryType);
    const dateText = formatChangelogDate(entry);

    let actorLine = 'Unknown user';
    const actorName = normalizeChangelogValue(entry.actorName);
    const actorEmail = normalizeChangelogValue(entry.actorEmail);
    if (actorName && actorEmail) {
        actorLine = `${actorName} (${actorEmail})`;
    } else if (actorName) {
        actorLine = actorName;
    } else if (actorEmail) {
        actorLine = actorEmail;
    }

    const wrapper = document.createElement('div');
    wrapper.className = 'changelog-entry';
    wrapper.dataset.changelogType = entryType;

    const marker = document.createElement('div');
    marker.className = 'changelog-entry-marker';
    if (badge.markerClass) {
        marker.classList.add(badge.markerClass);
    }

    const card = document.createElement('div');
    card.className = 'changelog-card';

    const header = document.createElement('div');
    header.className = 'changelog-header';

    const meta = document.createElement('div');
    meta.className = 'changelog-meta';

    const dateEl = document.createElement('div');
    dateEl.className = 'changelog-date';
    dateEl.textContent = dateText;

    const actorEl = document.createElement('div');
    actorEl.className = 'changelog-user';
    if (entryType === 'workflow') {
        actorEl.textContent = 'Workflow event';
    } else if (actorName && actorEmail) {
        actorEl.textContent = `${actorName} `;
        const emailSpan = document.createElement('span');
        emailSpan.textContent = `(${actorEmail})`;
        actorEl.appendChild(emailSpan);
    } else {
        actorEl.textContent = actorLine;
    }

    meta.appendChild(dateEl);
    meta.appendChild(actorEl);

    const badgeEl = document.createElement('span');
    badgeEl.className = badge.className;
    badgeEl.textContent = badge.label;

    header.appendChild(meta);
    header.appendChild(badgeEl);

    card.appendChild(header);

    const body = document.createElement('div');
    body.className = 'changelog-body';

    if (entryType === 'workflow') {
        const workflowBlock = document.createElement('div');
        workflowBlock.className = 'changelog-workflow-block';

        const actionLine = document.createElement('div');
        actionLine.className = 'changelog-workflow-action';

        const transition = inferWorkflowTransition(entry.eventName);
        actionLine.textContent = buildWorkflowSummary(entry.eventName, transition);
        const fromStep = transition.from || 'Unknown';
        const toStep = transition.to || 'Unknown';
        const flowLine = document.createElement('div');
        flowLine.className = 'changelog-workflow-flow';

        const fromEl = document.createElement('span');
        fromEl.className = 'changelog-step';
        fromEl.textContent = fromStep;

        const arrowEl = document.createElement('span');
        arrowEl.className = 'changelog-arrow';
        arrowEl.textContent = '->';

        const toEl = document.createElement('span');
        toEl.className = 'changelog-step';
        toEl.textContent = toStep;

        flowLine.appendChild(fromEl);
        flowLine.appendChild(arrowEl);
        flowLine.appendChild(toEl);

        workflowBlock.appendChild(actionLine);
        workflowBlock.appendChild(flowLine);

        if (shouldShowWorkflowComment(entry)) {
            const commentLine = document.createElement('div');
            commentLine.className = 'changelog-workflow-comment';
            commentLine.textContent = `Comment: ${entry.eventDetails}`;
            workflowBlock.appendChild(commentLine);
        }

        body.appendChild(workflowBlock);
    } else {
        const changes = buildFieldChanges(entry);
        if (!changes.changes.length) {
            return null;
        }

        const title = document.createElement('div');
        title.className = 'changelog-event-title';
        title.textContent = entry.eventName || 'Field Changes';
        body.appendChild(title);

        changes.changes.forEach(change => {
            const row = document.createElement('div');
            row.className = 'changelog-change-row';

            const fieldEl = document.createElement('span');
            fieldEl.className = 'changelog-field';
            fieldEl.textContent = `${change.field || 'Field'}:`;

            row.appendChild(fieldEl);

            if (changes.isSingle) {
                const valueEl = document.createElement('span');
                valueEl.className = 'changelog-new';
                valueEl.textContent = change.value;
                row.appendChild(valueEl);
            } else {
                const oldEl = document.createElement('span');
                oldEl.className = 'changelog-old';
                oldEl.textContent = change.oldValue || '(empty)';

                const arrowEl = document.createElement('span');
                arrowEl.className = 'changelog-arrow';
                arrowEl.textContent = '->';

                const newEl = document.createElement('span');
                newEl.className = 'changelog-new';
                newEl.textContent = change.newValue || '(empty)';

                row.appendChild(oldEl);
                row.appendChild(arrowEl);
                row.appendChild(newEl);
            }

            body.appendChild(row);
        });
    }

    card.appendChild(body);
    wrapper.appendChild(marker);
    wrapper.appendChild(card);

    return wrapper;
}

function loadChangelog(controlId) {
    const list = document.getElementById('changelogList');
    const loading = document.getElementById('changelogLoading');
    const empty = document.getElementById('changelogEmpty');

    if (!list || !loading || !empty) {
        return Promise.resolve();
    }

    loading.style.display = 'block';
    empty.style.display = 'none';
    list.innerHTML = '';

    return fetch(`/api/controls/${controlId}/changelog`)
        .then(response => {
            if (!response.ok) {
                throw new Error(`Failed to load changelog (${response.status})`);
            }
            return response.json();
        })
        .then(data => {
            loading.style.display = 'none';
            changelogEntries = Array.isArray(data) ? data : [];
            renderChangelog(changelogEntries, changelogFilter);
        })
        .catch(error => {
            console.error('Changelog load error:', error);
            loading.style.display = 'none';
            empty.style.display = 'block';
            empty.textContent = 'Failed to load history.';
        });
}

function renderChangelog(entries, activeFilter) {
    const list = document.getElementById('changelogList');
    const empty = document.getElementById('changelogEmpty');
    const toolbar = document.getElementById('changelogToolbar');

    if (!list || !empty) {
        return;
    }

    list.innerHTML = '';

    const safeEntries = Array.isArray(entries) ? entries : [];
    if (!safeEntries.length) {
        if (toolbar) {
            toolbar.classList.add('d-none');
        }
        empty.style.display = 'block';
        empty.textContent = 'No history available.';
        updateChangelogCount(0, 0);
        return;
    }

    if (toolbar) {
        toolbar.classList.remove('d-none');
    }
    initChangelogFilters();
    updateChangelogFilterButtons(activeFilter || 'all');

    const filterValue = activeFilter || 'all';
    let rendered = 0;
    let total = 0;
    safeEntries.forEach(entry => {
        const card = buildChangelogCard(entry);
        if (!card) {
            return;
        }
        total += 1;
        const type = card.dataset.changelogType || 'field';
        if (filterValue === 'all' || filterValue === type) {
            list.appendChild(card);
            rendered += 1;
        }
    });

    if (total === 0) {
        empty.style.display = 'block';
        empty.textContent = 'No history available.';
    } else if (rendered === 0) {
        empty.style.display = 'block';
        empty.textContent = 'No entries for this filter.';
    } else {
        empty.style.display = 'none';
    }

    updateChangelogCount(rendered, total);
}

function confirmWorkflowAction() {
    console.log('Confirming workflow action:', currentWorkflowAction);
    
    const commentInput = document.getElementById('workflowComment');
    const comment = commentInput ? commentInput.value.trim() : '';

    if (currentWorkflowRequiresComment && !comment) {
        alert('Please enter a comment');
        return;
    }

    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;

    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    // Close modal
    const modal = bootstrap.Modal.getInstance(document.getElementById('workflowActionModal'));
    if (modal) modal.hide();

    // Call performWorkflowAction
    performWorkflowAction(currentWorkflowAction, comment);
}

async function performWorkflowAction(action, comment) {
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;

    console.log('Performing workflow action:', { action, controlId, comment });

    try {
        const response = await fetch('/api/workflow/perform-action', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                controlId: controlId,
                action: action,
                comment: comment
            })
        });

        if (!response.ok) {
            const errorData = await response.json();
            throw new Error(errorData.message || 'Workflow action failed');
        }

        const data = await response.json();
        console.log('✅ Workflow action completed:', data);

        showAppModal({
            variant: 'success',
            title: 'Action Completed',
            message: data.message || 'Workflow action completed successfully',
            redirectUrl: '/'
        });

    } catch (error) {
        console.error('❌ Error performing workflow action:', error);
        
        showAppModal({
            variant: 'error',
            title: 'Action Failed',
            message: error.message || 'Failed to perform workflow action',
            autoCloseMs: 0
        });
    }
}

// Инициализация при загрузке DOM
document.addEventListener('DOMContentLoaded', async function() {
    console.log('🚀 DOM загружен, запускаем инициализацию...');
    
    // Инициализируем viewControl (включает обработчики для Edit, Changelog, Rename ID)
    await viewControl.init();
    
    // Показываем workflow кнопки с задержкой, если функция существует
    if (typeof window.showAllWorkflowButtons === 'function') {
        setTimeout(function() {
            window.showAllWorkflowButtons();
        }, 500);
    }

    const confirmSubmitSoqmLeadBtn = document.getElementById('confirmSubmitSoqmLeadBtn');
    if (confirmSubmitSoqmLeadBtn) {
        confirmSubmitSoqmLeadBtn.addEventListener('click', confirmSubmitToSoqmLead);
        console.log('✅ Confirm Submit to SoQM Lead handler added');
    }

    const confirmSharedSubmitSoqmBtn = document.getElementById('confirmSharedSubmitSoqmBtn');
    if (confirmSharedSubmitSoqmBtn) {
        confirmSharedSubmitSoqmBtn.addEventListener('click', confirmSharedSubmitToSoqmLead);
        console.log('✅ Confirm Shared Submit to SoQM Lead handler added');
    }

    const confirmReturnFacilitatorBtn = document.getElementById('confirmReturnFacilitatorBtn');
    if (confirmReturnFacilitatorBtn) {
        confirmReturnFacilitatorBtn.addEventListener('click', confirmReturnToFacilitator);
        console.log('✅ Confirm Return to Facilitator handler added');
    }

    // Bind SoQM Lead workflow buttons
    const confirmReturnOperatorBtn = document.getElementById('confirmReturnOperatorBtn');
    if (confirmReturnOperatorBtn) {
        confirmReturnOperatorBtn.addEventListener('click', confirmReturnToOperator);
        console.log('✅ Confirm Return to Operator handler added');
    }

    // Bind Process Owner workflow buttons
    const completeControlBtn = document.getElementById('completeControlBtn');
    if (completeControlBtn) {
        completeControlBtn.addEventListener('click', handleCompleteControl);
        console.log('✅ Complete Control button handler added');
    }

    const confirmCompleteControlBtn = document.getElementById('confirmCompleteControlBtn');
    if (confirmCompleteControlBtn) {
        confirmCompleteControlBtn.addEventListener('click', confirmCompleteControl);
        console.log('✅ Confirm Complete Control handler added');
    }

    const confirmReturnSoqmLeadBtn = document.getElementById('confirmReturnSoqmLeadBtn');
    if (confirmReturnSoqmLeadBtn) {
        confirmReturnSoqmLeadBtn.addEventListener('click', confirmReturnToSoqmLead);
        console.log('✅ Confirm Return to SoQM Lead handler added');
    }
});

async function confirmReturnToOperator() {
    console.log('🔘 Confirm Return to Operator');

    if (!await ensureWorkflowRoleReady()) {
        return;
    }
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    // Get optional comments
    const commentsElement = document.getElementById('returnOperatorComments');
    const comments = commentsElement ? commentsElement.value.trim() : '';

    // Build request URL with optional comments parameter
    let url = '/api/workflow/return-to-operator?controlId=' + controlId;
    if (comments) {
        url += '&comments=' + encodeURIComponent(comments);
    }

    submitWorkflowActionWithModal({
        url: url,
        confirmBtnId: 'confirmReturnOperatorBtn',
        confirmModalId: 'returnOperatorModal',
        successRedirectUrl: '/controls',
        successLogMessage: 'Return to Operator success -> showing popup',
        successTimerMs: 2500
    });
}

// ========== PROCESS OWNER WORKFLOW HANDLERS ==========
async function handleCompleteControl(event) {
    console.log('🔘 Complete Control button clicked');
    event.preventDefault();
    if (!await ensureWorkflowRoleReady()) {
        event.stopPropagation();
        return;
    }
    // Modal will be shown by Bootstrap's data-bs-toggle
}

async function confirmCompleteControl() {
    console.log('🔘 Confirm Complete Control');

    if (!await ensureWorkflowRoleReady()) {
        return;
    }
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    const confirmBtn = document.getElementById('confirmCompleteControlBtn');
    confirmBtn.disabled = true;
    confirmBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Completing...';

    fetch('/api/workflow/complete-control?controlId=' + controlId, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (!response.ok) {
            return response.text().then(text => {
                throw new Error('Error completing control: ' + text);
            });
        }
        return response.text();
    })
    .then(result => {
        console.log('✅ Control completed successfully');
        
        const modal = bootstrap.Modal.getInstance(document.getElementById('completeControlModal'));
        if (modal) {
            modal.hide();
        }

        showAppModal({
            variant: 'success',
            title: 'Control Completed',
            message: 'Control has been successfully completed and moved to final status',
            redirectUrl: '/controls'
        });
    })
    .catch(error => {
        console.error('❌ Error completing control:', error);
        
        showAppModal({
            variant: 'error',
            title: 'Completion Failed',
            message: error.message || 'Failed to complete control. Please try again.',
            autoCloseMs: 0
        });

        // Re-enable button
        confirmBtn.disabled = false;
        confirmBtn.innerHTML = 'Confirm Complete';
    });
}

async function confirmReturnToSoqmLead() {
    console.log('🔘 Confirm Return to SoQM Lead');

    if (!await ensureWorkflowRoleReady()) {
        return;
    }
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    // Get optional comments
    const commentsElement = document.getElementById('returnSoqmLeadComments');
    const comments = commentsElement ? commentsElement.value.trim() : '';

    // Build request URL with optional comments parameter
    let url = '/api/workflow/return-to-soqm-lead?controlId=' + controlId;
    if (comments) {
        url += '&comments=' + encodeURIComponent(comments);
    }

    submitWorkflowActionWithModal({
        url: url,
        confirmBtnId: 'confirmReturnSoqmLeadBtn',
        confirmModalId: 'returnSoqmLeadModal',
        successRedirectUrl: '/controls',
        successLogMessage: 'Return to SoQM Lead success -> showing popup',
        successTimerMs: 2500
    });
}

window.viewControl = viewControl;


