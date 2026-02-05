console.log('🔥 VIEW-CONTROL.JS v2.3 ЗАГРУЖЕН - ОЧИЩЕН ОТ НЕИСПОЛЬЗУЕМОГО КОДА');

const viewControl = (function() {
    let allUsers = [];
    let facilitatorUsers = [];
    let controlOperatorUsers = [];
    let soqmLeadUsers = [];
    let processOwnerUsers = [];
    let sharedWithUsers = [];

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
        'INITIATE': 'Initiate this control? Status will change from "Not Started" to "In Progress".',
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
                loadUsersByRole('FACILITATOR').then((users) => {
                    facilitatorUsers = users;
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
                loadUsersByRole('CONTROL_OPERATOR').then((users) => {
                    controlOperatorUsers = users;
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

                if (detailsData && detailsData.controlId) {
                    const form = document.getElementById('detailsForm');
                    if (form) {
                        Object.keys(detailsData).forEach(key => {
                            const field = form.querySelector(`[name="${key}"]`);
                            if (field && detailsData[key] && field.type !== 'file') {
                                field.value = detailsData[key];
                            }
                        });
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
                        if (documentsData.link) {
                            form.querySelector('[name="link"]').value = documentsData.link;
                        }
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

function makeAllFormsEditable() {
    console.log('=== MAKE ALL FORMS EDITABLE ===');

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
            field.style.opacity = '1';
        }
    });

    // 3. ASSIGNMENT TAB
    console.log('🔄 Processing Assignment tab fields...');
    const assignmentFields = document.querySelectorAll('#assignmentForm input, #assignmentForm select');
    const alwaysReadonlyFields = [
        'controlOperationDeadline',
        'nextControlOperationDate'
    ];
    const canEditAssignment = isSoqmLeadRole();

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
            field.style.opacity = '1';
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

    const controlData = {
        controlFrequency: controlForm.querySelector('[name="controlFrequency"]').value,
        controlCategory: controlForm.querySelector('[name="controlCategory"]').value,
        controlType: controlForm.querySelector('[name="controlType"]').value,
        component: controlForm.querySelector('[name="component"]').value,
        operatedBy: controlForm.querySelector('[name="operatedBy"]').value,
        controlStatus: controlForm.querySelector('[name="controlStatus"]').value,
        priority: controlForm.querySelector('[name="priority"]').value,
        nonAuditServicesApplicability: controlForm.querySelector('[name="nonAuditServicesApplicability"]').value,
        controlDescription: controlForm.querySelector('[name="controlDescription"]').value,
        prp: controlForm.querySelector('[name="prp"]').value
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
    Swal.fire({
        icon: 'success',
        title: 'Saved Successfully',
        text: 'Control information has been saved',
        confirmButtonText: 'OK',
        timer: 2000,
        timerProgressBar: true
    });

    setTimeout(() => {
        console.log('🔄 Redirecting to controls page...');
        window.location.href = '/controls';
    }, 2000);

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
                    field.style.pointerEvents = 'none';
                    field.style.opacity = '0.6';
                    field.value = ''; // Clear file selection
                    
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
            editBtn.classList.remove('btn-success');
            editBtn.classList.add('btn-primary');
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
            Swal.fire({
                icon: 'success',
                title: 'Saved Successfully',
                text: 'All control data has been saved',
                confirmButtonText: 'OK',
                timer: 2000,
                timerProgressBar: true
            });

            // Редирект на главную
            setTimeout(() => {
                console.log('🔄 Redirecting to controls page...');
                window.location.href = '/controls';
            }, 2100);

            return { success: true };
        })
        .catch(error => {
            console.error('❌ Error saving control data:', error);

            Swal.fire({
                icon: 'error',
                title: 'Save Failed',
                text: 'Error saving control: ' + error.message,
                confirmButtonText: 'OK'
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
        Swal.fire({
            icon: 'success',
            title: 'Saved Successfully',
            text: 'Assignment data has been saved',
            confirmButtonText: 'OK',
            timer: 2000,
            timerProgressBar: true
        });

        // Редирект через 2 секунды (после закрытия алерта)
        setTimeout(() => {
            console.log('🔄 Redirecting to controls page');
            window.location.href = '/controls';
        }, 2000);

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

    const detailsForm = document.getElementById('detailsForm');
    if (!detailsForm) {
        return Promise.reject('Details form not found');
    }

    const detailsData = {
        controlId: parseInt(controlId),
        processName: detailsForm.querySelector('[name="processName"]').value,
        homogeneity: detailsForm.querySelector('[name="homogeneity"]').value,
        referencesToControl: detailsForm.querySelector('[name="referencesToControl"]').value,
        department: detailsForm.querySelector('[name="department"]').value,
        processActivities: detailsForm.querySelector('[name="processActivities"]').value,
        controlOperatorsProgram: detailsForm.querySelector('[name="controlOperatorsProgram"]').value,
        otherRelatedControls: detailsForm.querySelector('[name="otherRelatedControls"]').value,
        itApplications: detailsForm.querySelector('[name="itApplications"]').value,
        controlStepsPerformed: detailsForm.querySelector('[name="controlStepsPerformed"]').value,
        soqmHeadComments: detailsForm.querySelector('[name="soqmHeadComments"]').value,
        processOwnerComments: detailsForm.querySelector('[name="processOwnerComments"]').value,
        attachedFile: detailsForm.querySelector('[name="attachedFile"]').value
    };

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

        // Upload file attachments if any
        if (window.uploadAttachments) {
            console.log('📤 Uploading file attachments...');
            window.uploadAttachments();
        }

        Swal.fire({
            icon: 'success',
            title: 'Saved Successfully',
            text: 'Details have been saved',
            confirmButtonText: 'OK',
            timer: 2000,
            timerProgressBar: true
        });

        // Редирект через 2.1 секунды
        setTimeout(() => {
            console.log('🔄 Redirecting to controls page...');
            window.location.href = '/controls';
        }, 2100);

        return data;
    })
    .catch(error => {
        console.error('❌ Error saving details:', error);

        Swal.fire({
            icon: 'error',
            title: 'Save Failed',
            text: 'Error saving details: ' + error.message,
            confirmButtonText: 'OK'
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

    const documentsData = {
        controlId: parseInt(controlId),
        link: documentsForm.querySelector('[name="link"]').value,
        attachment: documentsForm.querySelector('[name="attachment"]').value,
        soqmDevelopmentMaterials: documentsForm.querySelector('[name="soqmDevelopmentMaterials"]').value
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

    Swal.fire({
        icon: 'success',
        title: 'Saved Successfully',
        text: 'Documents have been saved',
        confirmButtonText: 'OK',
        timer: 2000,
        timerProgressBar: true
    });

    // Редирект через 2.1 секунды
    setTimeout(() => {
        console.log('🔄 Redirecting to controls page...');
        window.location.href = '/controls';
    }, 2100);

    return data;
})
    .catch(error => {
        console.error('❌ Unexpected error in saveDocumentsData:', error);

        Swal.fire({
            icon: 'error',
            title: 'Save Failed',
            text: 'Error saving documents: ' + error.message,
            confirmButtonText: 'OK'
        });

        // Не бросаем ошибку дальше
        return { success: false, caughtError: error.message };
    });
}


    function updateCalculatedDates() {
        const controlOperationDateInput = document.querySelector('input[name="controlOperationDate"]');
        if (controlOperationDateInput && controlOperationDateInput.value) {
            const event = new Event('change');
            controlOperationDateInput.dispatchEvent(event);
            console.log('Updated calculated dates');
        }
    }

    function calculateDeadline(operationDate, controlFrequency) {
        const date = new Date(operationDate.getFullYear(), operationDate.getMonth(), operationDate.getDate());

        if (!controlFrequency) {
            date.setDate(date.getDate() + 7);
            return date;
        }

        const freq = controlFrequency.toLowerCase();
        const normalized = freq.replace(/\s+/g, ' ').trim();
        const hasSemiAnnual = normalized.includes('semi-annual') || normalized.includes('semi annual') ||
            normalized.includes('semi-annually') || normalized.includes('semi annually');
        const hasAnnual = normalized.includes('annual');
        const hasRequiredAnnual = normalized.includes('required') && normalized.includes('annual');
        const hasQuarterly = normalized.includes('quarterly');
        const hasMonthly = normalized.includes('monthly');
        const hasRecurring = normalized.includes('recurring');
        const hasWeekly = normalized.includes('weekly');
        const hasDaily = normalized.includes('daily');
        const hasAdHoc = normalized.includes('ad-hoc') || normalized.includes('ad hoc');

        if (hasSemiAnnual || hasAnnual || hasRequiredAnnual) {
            date.setMonth(date.getMonth() + 1);
        } else if (hasQuarterly) {
            date.setDate(date.getDate() + 14);
        } else if (hasMonthly || hasRecurring) {
            date.setDate(date.getDate() + 7);
        } else if (hasWeekly) {
            date.setDate(date.getDate() + 5);
        } else if (hasDaily) {
            date.setDate(date.getDate() + 1);
        } else if (hasAdHoc) {
            date.setDate(date.getDate() + 7);
        } else {
            date.setDate(date.getDate() + 7);
        }

        return date;
    }

    function calculateNextOperationDate(operationDate, controlFrequency) {
        const date = new Date(operationDate.getFullYear(), operationDate.getMonth(), operationDate.getDate());

        if (!controlFrequency) {
            date.setMonth(date.getMonth() + 1);
            return date;
        }

        const freq = controlFrequency.toLowerCase();
        const normalized = freq.replace(/\s+/g, ' ').trim();
        const hasSemiAnnual = normalized.includes('semi-annual') || normalized.includes('semi annual') ||
            normalized.includes('semi-annually') || normalized.includes('semi annually');
        const hasAnnual = normalized.includes('annual');
        const hasRequiredAnnual = normalized.includes('required') && normalized.includes('annual');
        const hasQuarterly = normalized.includes('quarterly');
        const hasMonthly = normalized.includes('monthly');
        const hasRecurring = normalized.includes('recurring');
        const hasWeekly = normalized.includes('weekly');
        const hasDaily = normalized.includes('daily');
        const hasAdHoc = normalized.includes('ad-hoc') || normalized.includes('ad hoc');

        if (hasSemiAnnual) {
            date.setMonth(date.getMonth() + 6);
        } else if (hasRequiredAnnual || hasAnnual) {
            date.setMonth(date.getMonth() + 12);
        } else if (hasQuarterly) {
            date.setMonth(date.getMonth() + 3);
        } else if (hasMonthly || hasRecurring) {
            date.setMonth(date.getMonth() + 1);
        } else if (hasWeekly) {
            date.setDate(date.getDate() + 7);
        } else if (hasDaily) {
            date.setDate(date.getDate() + 1);
        } else if (hasAdHoc) {
            date.setMonth(date.getMonth() + 1);
        } else {
            date.setMonth(date.getMonth() + 1);
        }

        return date;
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

            document.querySelector('input[name="controlOperationDate"]')?.addEventListener('change', function() {
                console.log('Control Operation Date changed:', this.value);

                const operationDate = parseIsoDate(this.value);
                if (operationDate) {
                    const controlFrequency = document.querySelector('#controlForm select[name="controlFrequency"]')?.value;
                    console.log('Control Frequency:', controlFrequency);

                    const deadline = calculateDeadline(operationDate, controlFrequency);
                    const nextOperationDate = calculateNextOperationDate(operationDate, controlFrequency);

                    const deadlineInput = document.querySelector('input[name="controlOperationDeadline"]');
                    const nextDateInput = document.querySelector('input[name="nextControlOperationDate"]');

                    if (deadlineInput) {
                        setDateFieldValue(deadlineInput, toIsoDate(deadline));
                        console.log('Set deadline to:', deadlineInput.value);
                    }
                    if (nextDateInput) {
                        setDateFieldValue(nextDateInput, toIsoDate(nextOperationDate));
                        console.log('Set next date to:', nextDateInput.value);
                    }
                }
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

                const forceRemoveModalBackdrop = () => {
                    const backdrops = document.querySelectorAll('.modal-backdrop');
                    backdrops.forEach(backdrop => backdrop.remove());
                    document.body.classList.remove('modal-open');
                    document.body.style.overflow = '';
                    document.body.style.paddingRight = '';
                };

                forceRemoveModalBackdrop();

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
                        setTimeout(forceRemoveModalBackdrop, 100);
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

                        renameModal.hide();

                        setTimeout(() => {
                            forceRemoveModalBackdrop();
                            Swal.fire({
                                icon: 'success',
                                title: 'Renamed Successfully',
                                text: 'Control ID has been renamed to ' + updatedControl.controlId,
                                confirmButtonText: 'OK',
                                timer: 2000,
                                timerProgressBar: true
                            }).then(() => {
                                window.location.href = '/';
                            });
                        }, 300);

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
                        setTimeout(forceRemoveModalBackdrop, 100);
                    };
                }

                const closeBtn = modalElement.querySelector('.btn-close[data-bs-dismiss="modal"]');
                if (closeBtn) {
                    closeBtn.onclick = function() {
                        renameModal.hide();
                        setTimeout(forceRemoveModalBackdrop, 100);
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

            // Добавляем обработчик для Submit to Control Operator button
            const submitToControlOperatorBtn = document.getElementById('submitToControlOperatorBtn');
            if (submitToControlOperatorBtn) {
                submitToControlOperatorBtn.addEventListener('click', handleSubmitToControlOperator);
                console.log('✅ Submit to Control Operator button handler added');
            } else {
                console.warn('⚠️ Submit to Control Operator button not found');
            }

            // Добавляем обработчик для подтверждения Submit to Control Operator
            const confirmSubmitBtn = document.getElementById('confirmSubmitBtn');
            if (confirmSubmitBtn) {
                confirmSubmitBtn.addEventListener('click', confirmSubmitToControlOperator);
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
                const controlStatusInput = document.querySelector('input[name="controlStatus"]');
                const controlStatus = controlStatusInput ? controlStatusInput.value : '';
                const isFacilitatorFlag = document.getElementById('isFacilitator')?.value === 'true';
                const isControlOperatorFlag = document.getElementById('isControlOperator')?.value === 'true';
                const isSoqmLeadFlag = document.getElementById('isSoqmLead')?.value === 'true';
                const isProcessOwnerFlag = document.getElementById('isProcessOwner')?.value === 'true';

                console.log('Final check - Role:', currentUserRole, 'Performance Status:', currentStatus, 'Control Status:', controlStatus);
                console.log('Is Facilitator for this control:', isFacilitatorFlag);
                console.log('Is Control Operator for this control:', isControlOperatorFlag);
                console.log('Is SoQM Lead for this control:', isSoqmLeadFlag);
                console.log('Is Process Owner for this control:', isProcessOwnerFlag);

                // Only lock form if control is actually in workflow
                if (controlStatus && controlStatus !== '' && controlStatus !== 'Not Started' && controlStatus !== 'In Progress') {
                    if (controlStatus === 'Facilitator Review') {
                        // Control is in Facilitator Review
                        if (isFacilitatorFlag) {
                            // Show Submit to Control Operator button for assigned Facilitator
                            const submitToOperatorBtn = document.getElementById('submitToControlOperatorBtn');
                            if (submitToOperatorBtn) {
                                submitToOperatorBtn.style.display = 'inline-block';
                                console.log('✅ Button "Submit to Control Operator" shown for assigned Facilitator!');
                            }
                            // Facilitator can edit during Facilitator Review
                            console.log('✅ Assigned Facilitator can edit control in Facilitator Review');
                        } else {
                            // For non-Facilitators, lock the control
                            console.log('🔒 Control in Facilitator Review - locking for non-Facilitator');
                            lockControlForm();
                        }
                    } else if (controlStatus === 'Control Operator Review') {
                        // Control is in Control Operator Review
                        if (isControlOperatorFlag) {
                            // Control Operator can edit during Control Operator Review
                            console.log('✅ Assigned Control Operator can edit control in Control Operator Review');
                        } else {
                            // For non-Control Operators, lock the control
                            console.log('🔒 Control in Control Operator Review - locking for non-Control Operator');
                            lockControlForm();
                        }
                    } else if (controlStatus === 'SoQM Lead Review') {
                        // Control is in SoQM Lead Review
                        if (isSoqmLeadFlag) {
                            // SoQM Lead can edit during SoQM Lead Review
                            console.log('✅ Assigned SoQM Lead can edit control in SoQM Lead Review');
                        } else {
                            // For non-SoQM Leads, lock the control
                            console.log('🔒 Control in SoQM Lead Review - locking for non-SoQM Lead');
                            lockControlForm();
                        }
                    } else if (controlStatus === 'Process Owner Review') {
                        // Control is in Process Owner Review
                        if (isProcessOwnerFlag) {
                            // Process Owner can edit during Process Owner Review
                            console.log('✅ Assigned Process Owner can edit control in Process Owner Review');
                        } else {
                            // For non-Process Owners, lock the control
                            console.log('🔒 Control in Process Owner Review - locking for non-Process Owner');
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

// ========== SUBMIT TO CONTROL OPERATOR HANDLER ==========
function handleSubmitToControlOperator() {
    const controlId = document.querySelector('input[name="id"]')?.value;
    const userRole = document.getElementById('currentUserRole')?.value;

    console.log(`📋 Submit to Control Operator - Control ID: ${controlId}, Role: ${userRole}`);

    if (!controlId) {
        showErrorMessage('Control ID not found');
        return;
    }

    if (userRole !== 'FACILITATOR') {
        showErrorMessage('Only Facilitator can submit controls');
        return;
    }

    // Show confirmation modal
    const modal = new bootstrap.Modal(document.getElementById('submitConfirmationModal'));
    modal.show();
}

function confirmSubmitToControlOperator() {
    const controlId = document.querySelector('input[name="id"]')?.value;
    const submitBtn = document.getElementById('submitToControlOperatorBtn');

    if (!controlId) {
        showErrorMessage('Control ID not found');
        return;
    }

    // Disable button to prevent double-click
    submitBtn.disabled = true;
    submitBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Submitting...';

    // Call API endpoint
    fetch(`/api/workflow/submit-to-control-operator?controlId=${controlId}`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (!response.ok) {
            if (response.status === 403) {
                throw new Error('Only Facilitator can submit controls');
            } else if (response.status === 404) {
                throw new Error('Control not found');
            } else if (response.status === 401) {
                throw new Error('Unauthorized - please log in again');
            }
            throw new Error('Failed to submit control');
        }
        return response.json();
    })
    .then(data => {
        if (data.success) {
            console.log('✅ Control submitted successfully:', data);

            // Close the confirmation modal
            const modal = bootstrap.Modal.getInstance(document.getElementById('submitConfirmationModal'));
            if (modal) modal.hide();

            // Redirect to /controls page immediately
            window.location.href = '/controls';
        } else {
            throw new Error(data.message || 'Submission failed');
        }
    })
    .catch(error => {
        console.error('❌ Error submitting control:', error);
        showErrorMessage(error.message || 'Failed to submit control. Please try again.');

        // Re-enable button
        submitBtn.disabled = false;
        submitBtn.innerHTML = 'Submit to Control Operator';
    });
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

// ========== SUBMIT TO CONTROL OPERATOR HANDLERS ==========
function handleSubmitToControlOperator(event) {
    console.log('🔘 Submit to Control Operator button clicked');
    event.preventDefault();
    // Modal will be shown by Bootstrap's data-bs-toggle
}

function confirmSubmitToControlOperator() {
    console.log('🔘 Confirm Submit to Control Operator');
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    const confirmBtn = document.getElementById('confirmSubmitBtn');
    confirmBtn.disabled = true;
    confirmBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Submitting...';

    // Call the backend API
    fetch('/api/workflow/submit-to-control-operator?controlId=' + controlId, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (!response.ok) {
            return response.json().then(data => {
                throw new Error(data.message || 'Failed to submit control');
            });
        }
        return response.json();
    })
    .then(data => {
        console.log('✅ Control submitted successfully:', data);

        // Close the modal
        const modal = bootstrap.Modal.getInstance(document.getElementById('submitConfirmationModal'));
        if (modal) modal.hide();

        // Redirect to dashboard
        window.location.href = '/';
    })
    .catch(error => {
        console.error('❌ Error submitting control:', error);
        
        Swal.fire({
            icon: 'error',
            title: 'Submission Failed',
            text: error.message || 'Failed to submit control. Please try again.',
            confirmButtonText: 'OK'
        });

        // Re-enable button
        confirmBtn.disabled = false;
        confirmBtn.innerHTML = 'Confirm Submission';
    });
}

// ========== WORKFLOW BUTTON HANDLERS ==========

// ========== SUBMIT TO SOQM LEAD HANDLERS (Control Operator → SoQM Lead) ==========
function handleSubmitToSoqmLead(event) {
    console.log('🔘 Submit to SoQM Lead button clicked');
    event.preventDefault();
    // Modal will be shown by Bootstrap's data-bs-toggle
}

function confirmSubmitToSoqmLead() {
    console.log('🔘 Confirm Submit to SoQM Lead');
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    const confirmBtn = document.getElementById('confirmSubmitSoqmLeadBtn');
    confirmBtn.disabled = true;
    confirmBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Submitting...';

    // Call the backend API
    fetch('/api/workflow/submit-to-soqm-lead?controlId=' + controlId, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (!response.ok) {
            return response.json().then(data => {
                throw new Error(data.message || 'Failed to submit control to SoQM Lead');
            });
        }
        return response.json();
    })
    .then(data => {
        console.log('✅ Control submitted to SoQM Lead successfully:', data);

        // Close the modal
        const modal = bootstrap.Modal.getInstance(document.getElementById('submitSoqmLeadModal'));
        if (modal) modal.hide();

        // Show success message
        Swal.fire({
            icon: 'success',
            title: 'Submitted Successfully',
            text: data.message || 'Control has been submitted to SoQM Lead for review',
            confirmButtonText: 'OK',
            timer: 1200,
            timerProgressBar: true
        }).then(() => {
            // Go back to dashboard
            window.location.href = '/';
        });
    })
    .catch(error => {
        console.error('❌ Error submitting control to SoQM Lead:', error);
        
        Swal.fire({
            icon: 'error',
            title: 'Submission Failed',
            text: error.message || 'Failed to submit control. Please try again.',
            confirmButtonText: 'OK'
        });

        // Re-enable button
        confirmBtn.disabled = false;
        confirmBtn.innerHTML = 'Confirm Submission';
    });
}

// ========== RETURN TO FACILITATOR HANDLERS (Control Operator → Facilitator) ==========
function handleReturnToFacilitator(event) {
    console.log('🔘 Return to Facilitator button clicked');
    event.preventDefault();
    // Modal will be shown by Bootstrap's data-bs-toggle
}

function confirmReturnToFacilitator() {
    console.log('🔘 Confirm Return to Facilitator');
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    // Get optional comments
    const commentsElement = document.getElementById('returnComments');
    const comments = commentsElement ? commentsElement.value.trim() : '';

    const confirmBtn = document.getElementById('confirmReturnFacilitatorBtn');
    confirmBtn.disabled = true;
    confirmBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Returning...';

    // Build request URL with optional comments parameter
    let url = '/api/workflow/return-to-facilitator?controlId=' + controlId;
    if (comments) {
        url += '&comments=' + encodeURIComponent(comments);
    }

    // Call the backend API
    fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (!response.ok) {
            return response.json().then(data => {
                throw new Error(data.message || 'Failed to return control to Facilitator');
            });
        }
        return response.json();
    })
    .then(data => {
        console.log('✅ Control returned to Facilitator successfully:', data);

        // Close the modal
        const modal = bootstrap.Modal.getInstance(document.getElementById('returnFacilitatorModal'));
        if (modal) modal.hide();

        // Redirect to /controls page immediately
        window.location.href = '/controls';
    })
    .catch(error => {
        console.error('❌ Error returning control to Facilitator:', error);
        
        Swal.fire({
            icon: 'error',
            title: 'Return Failed',
            text: error.message || 'Failed to return control. Please try again.',
            confirmButtonText: 'OK'
        });

        // Re-enable button
        confirmBtn.disabled = false;
        confirmBtn.innerHTML = 'Confirm Return';
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
            if (!data || data.length === 0) {
                empty.style.display = 'block';
                return;
            }
            renderChangelog(data);
        })
        .catch(error => {
            console.error('Changelog load error:', error);
            loading.style.display = 'none';
            empty.style.display = 'block';
            empty.textContent = 'Failed to load history.';
        });
}

function renderChangelog(entries) {
    const list = document.getElementById('changelogList');
    if (!list) return;

    list.innerHTML = '';

    entries.forEach(entry => {
        const wrapper = document.createElement('div');
        wrapper.className = 'border rounded p-3 mb-3';

        const header = document.createElement('div');
        header.className = 'fw-semibold';
        const actorName = entry.actorName ? entry.actorName : '';
        const actorEmail = entry.actorEmail ? entry.actorEmail : '';
        const actorLine = actorName && actorEmail
            ? `By: ${actorName} - ${actorEmail}`
            : actorName
                ? `By: ${actorName}`
                : actorEmail
                    ? `By: ${actorEmail}`
                    : 'By: -';
        const datePart = (window.QTrackerDate && entry.createdAt)
            ? window.QTrackerDate.formatDisplayDateFromIso(entry.createdAt)
            : (entry.formattedTime ? entry.formattedTime : '-');
        const headerText = `# [${datePart}] [${actorLine}]`;
        header.textContent = headerText;
        wrapper.appendChild(header);

        const eventLine = document.createElement('div');
        eventLine.className = 'mt-1';
        eventLine.textContent = `Event: ${entry.eventName || 'Event'}`;
        wrapper.appendChild(eventLine);

        if (entry.eventDetails) {
            const details = document.createElement('div');
            details.className = 'text-muted small mt-1';
            details.textContent = entry.eventDetails;
            wrapper.appendChild(details);
        }

        if (entry.fieldChanges && entry.fieldChanges.length > 0) {
            const tableWrapper = document.createElement('div');
            tableWrapper.className = 'table-responsive mt-2';

            const table = document.createElement('table');
            table.className = 'table table-sm table-bordered mb-0';

            const thead = document.createElement('thead');
            const tableType = entry.tableType || 'DIFF';
            thead.innerHTML = tableType === 'SINGLE'
                ? '<tr><th>Field</th><th>Value</th></tr>'
                : '<tr><th>Field</th><th>Original Content</th><th>Modified Content</th></tr>';

            const tbody = document.createElement('tbody');
            entry.fieldChanges.forEach(change => {
                const row = document.createElement('tr');
                const fieldCell = document.createElement('td');
                fieldCell.textContent = change.field || '';
                const oldCell = document.createElement('td');
                const newCell = document.createElement('td');
                if (tableType === 'SINGLE') {
                    oldCell.textContent = change.newValue || change.oldValue || '';
                } else {
                    oldCell.textContent = change.oldValue || '';
                    newCell.textContent = change.newValue || '';
                }
                row.appendChild(fieldCell);
                row.appendChild(oldCell);
                if (tableType !== 'SINGLE') {
                    row.appendChild(newCell);
                }
                tbody.appendChild(row);
            });

            table.appendChild(thead);
            table.appendChild(tbody);
            tableWrapper.appendChild(table);
            wrapper.appendChild(tableWrapper);
        }

        list.appendChild(wrapper);
    });
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

        Swal.fire({
            icon: 'success',
            title: 'Action Completed',
            text: data.message || 'Workflow action completed successfully',
            confirmButtonText: 'OK',
            timer: 1200,
            timerProgressBar: true
        }).then(() => {
            window.location.href = '/';
        });

    } catch (error) {
        console.error('❌ Error performing workflow action:', error);
        
        Swal.fire({
            icon: 'error',
            title: 'Action Failed',
            text: error.message || 'Failed to perform workflow action',
            confirmButtonText: 'OK'
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

    // Bind Control Operator workflow buttons if present
    const submitToSoqmLeadBtn = document.getElementById('submitToSoqmLeadBtn');
    if (submitToSoqmLeadBtn) {
        submitToSoqmLeadBtn.addEventListener('click', handleSubmitToSoqmLead);
        console.log('✅ Submit to SoQM Lead button handler added');
    }

    const confirmSubmitSoqmLeadBtn = document.getElementById('confirmSubmitSoqmLeadBtn');
    if (confirmSubmitSoqmLeadBtn) {
        confirmSubmitSoqmLeadBtn.addEventListener('click', confirmSubmitToSoqmLead);
        console.log('✅ Confirm Submit to SoQM Lead handler added');
    }

    const returnToFacilitatorBtn = document.getElementById('returnToFacilitatorBtn');
    if (returnToFacilitatorBtn) {
        returnToFacilitatorBtn.addEventListener('click', handleReturnToFacilitator);
        console.log('✅ Return to Facilitator button handler added');
    }

    const confirmReturnFacilitatorBtn = document.getElementById('confirmReturnFacilitatorBtn');
    if (confirmReturnFacilitatorBtn) {
        confirmReturnFacilitatorBtn.addEventListener('click', confirmReturnToFacilitator);
        console.log('✅ Confirm Return to Facilitator handler added');
    }

    // Bind SoQM Lead workflow buttons
    const submitToProcessOwnerBtn = document.getElementById('submitToProcessOwnerBtn');
    if (submitToProcessOwnerBtn) {
        submitToProcessOwnerBtn.addEventListener('click', handleSubmitToProcessOwner);
        console.log('✅ Submit to Process Owner button handler added');
    }

    const confirmSubmitProcessOwnerBtn = document.getElementById('confirmSubmitProcessOwnerBtn');
    if (confirmSubmitProcessOwnerBtn) {
        confirmSubmitProcessOwnerBtn.addEventListener('click', confirmSubmitToProcessOwner);
        console.log('✅ Confirm Submit to Process Owner handler added');
    }

    const returnToOperatorBtn = document.getElementById('returnToOperatorBtn');
    if (returnToOperatorBtn) {
        returnToOperatorBtn.addEventListener('click', handleReturnToOperator);
        console.log('✅ Return to Operator button handler added');
    }

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

    const returnToSoqmLeadBtn = document.getElementById('returnToSoqmLeadBtn');
    if (returnToSoqmLeadBtn) {
        returnToSoqmLeadBtn.addEventListener('click', handleReturnToSoqmLead);
        console.log('✅ Return to SoQM Lead button handler added');
    }

    const confirmReturnSoqmLeadBtn = document.getElementById('confirmReturnSoqmLeadBtn');
    if (confirmReturnSoqmLeadBtn) {
        confirmReturnSoqmLeadBtn.addEventListener('click', confirmReturnToSoqmLead);
        console.log('✅ Confirm Return to SoQM Lead handler added');
    }
});

// ========== SOQM LEAD WORKFLOW HANDLERS ==========
function handleSubmitToProcessOwner(event) {
    console.log('🔘 Submit to Process Owner button clicked');
    event.preventDefault();
    // Modal will be shown by Bootstrap's data-bs-toggle
}

function confirmSubmitToProcessOwner() {
    console.log('🔘 Confirm Submit to Process Owner');
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    const confirmBtn = document.getElementById('confirmSubmitProcessOwnerBtn');
    confirmBtn.disabled = true;
    confirmBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Submitting...';

    const url = '/api/workflow/submit-to-process-owner?controlId=' + controlId;
    
    fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (!response.ok) {
            return response.text().then(text => {
                throw new Error('Error submitting control: ' + text);
            });
        }
        return response.text();
    })
    .then(result => {
        console.log('✅ Control submitted to Process Owner successfully');
        
        Swal.fire({
            icon: 'success',
            title: 'Submitted Successfully',
            text: 'Control has been submitted to Process Owner for review',
            confirmButtonText: 'OK',
            timer: 2000,
            timerProgressBar: true
        }).then(() => {
            // Close modal
            const modal = bootstrap.Modal.getInstance(document.getElementById('submitProcessOwnerModal'));
            if (modal) modal.hide();
            
            // Redirect to controls page
            window.location.href = '/controls';
        });
    })
    .catch(error => {
        console.error('❌ Error submitting control to Process Owner:', error);
        
        Swal.fire({
            icon: 'error',
            title: 'Submission Failed',
            text: error.message || 'Failed to submit control. Please try again.',
            confirmButtonText: 'OK'
        });

        // Re-enable button
        confirmBtn.disabled = false;
        confirmBtn.innerHTML = 'Confirm Submission';
    });
}

function handleReturnToOperator(event) {
    console.log('🔘 Return to Operator button clicked');
    event.preventDefault();
    // Modal will be shown by Bootstrap's data-bs-toggle
}

function confirmReturnToOperator() {
    console.log('🔘 Confirm Return to Operator');
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    // Get optional comments
    const commentsElement = document.getElementById('returnOperatorComments');
    const comments = commentsElement ? commentsElement.value.trim() : '';

    const confirmBtn = document.getElementById('confirmReturnOperatorBtn');
    confirmBtn.disabled = true;
    confirmBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Returning...';

    // Build request URL with optional comments parameter
    let url = '/api/workflow/return-to-operator?controlId=' + controlId;
    if (comments) {
        url += '&comments=' + encodeURIComponent(comments);
    }

    fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (!response.ok) {
            return response.text().then(text => {
                throw new Error('Error returning control: ' + text);
            });
        }
        return response.text();
    })
    .then(result => {
        console.log('✅ Control returned to Operator successfully');
        
        Swal.fire({
            icon: 'success',
            title: 'Returned Successfully',
            text: 'Control has been returned to Control Operator for revision',
            confirmButtonText: 'OK',
            timer: 2000,
            timerProgressBar: true
        }).then(() => {
            // Close modal
            const modal = bootstrap.Modal.getInstance(document.getElementById('returnOperatorModal'));
            if (modal) modal.hide();
            
            // Redirect to controls page
            window.location.href = '/controls';
        });
    })
    .catch(error => {
        console.error('❌ Error returning control to Operator:', error);
        
        Swal.fire({
            icon: 'error',
            title: 'Return Failed',
            text: error.message || 'Failed to return control. Please try again.',
            confirmButtonText: 'OK'
        });

        // Re-enable button
        confirmBtn.disabled = false;
        confirmBtn.innerHTML = 'Confirm Return';
    });
}

// ========== PROCESS OWNER WORKFLOW HANDLERS ==========
function handleCompleteControl(event) {
    console.log('🔘 Complete Control button clicked');
    event.preventDefault();
    // Modal will be shown by Bootstrap's data-bs-toggle
}

function confirmCompleteControl() {
    console.log('🔘 Confirm Complete Control');
    
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
        
        Swal.fire({
            icon: 'success',
            title: 'Control Completed',
            text: 'Control has been successfully completed and moved to final status',
            confirmButtonText: 'OK',
            timer: 2000,
            timerProgressBar: true
        }).then(() => {
            // Close modal
            const modal = bootstrap.Modal.getInstance(document.getElementById('completeControlModal'));
            if (modal) modal.hide();
            
            // Redirect to controls page
            window.location.href = '/controls';
        });
    })
    .catch(error => {
        console.error('❌ Error completing control:', error);
        
        Swal.fire({
            icon: 'error',
            title: 'Completion Failed',
            text: error.message || 'Failed to complete control. Please try again.',
            confirmButtonText: 'OK'
        });

        // Re-enable button
        confirmBtn.disabled = false;
        confirmBtn.innerHTML = 'Confirm Complete';
    });
}

function handleReturnToSoqmLead(event) {
    console.log('🔘 Return to SoQM Lead button clicked');
    event.preventDefault();
    // Modal will be shown by Bootstrap's data-bs-toggle
}

function confirmReturnToSoqmLead() {
    console.log('🔘 Confirm Return to SoQM Lead');
    
    const controlIdElement = document.querySelector('input[name="id"]');
    const controlId = controlIdElement ? controlIdElement.value : null;
    
    if (!controlId) {
        alert('Error: Control ID not found');
        return;
    }

    // Get optional comments
    const commentsElement = document.getElementById('returnSoqmLeadComments');
    const comments = commentsElement ? commentsElement.value.trim() : '';

    const confirmBtn = document.getElementById('confirmReturnSoqmLeadBtn');
    confirmBtn.disabled = true;
    confirmBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Returning...';

    // Build request URL with optional comments parameter
    let url = '/api/workflow/return-to-soqm-lead?controlId=' + controlId;
    if (comments) {
        url += '&comments=' + encodeURIComponent(comments);
    }

    fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (!response.ok) {
            return response.text().then(text => {
                throw new Error('Error returning control: ' + text);
            });
        }
        return response.text();
    })
    .then(result => {
        console.log('✅ Control returned to SoQM Lead successfully');
        
        Swal.fire({
            icon: 'success',
            title: 'Returned Successfully',
            text: 'Control has been returned to SoQM Lead for revision',
            confirmButtonText: 'OK',
            timer: 2000,
            timerProgressBar: true
        }).then(() => {
            // Close modal
            const modal = bootstrap.Modal.getInstance(document.getElementById('returnSoqmLeadModal'));
            if (modal) modal.hide();
            
            // Redirect to controls page
            window.location.href = '/controls';
        });
    })
    .catch(error => {
        console.error('❌ Error returning control to SoQM Lead:', error);
        
        Swal.fire({
            icon: 'error',
            title: 'Return Failed',
            text: error.message || 'Failed to return control. Please try again.',
            confirmButtonText: 'OK'
        });

        // Re-enable button
        confirmBtn.disabled = false;
        confirmBtn.innerHTML = 'Confirm Return';
    });
}

window.viewControl = viewControl;

