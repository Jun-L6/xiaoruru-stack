(() => {
  // 原生 file input 保留键盘和表单语义，外层只负责可视化状态与拖放转交。
  document.querySelectorAll('[data-file-picker]').forEach((picker) => {
    const input = picker.querySelector('input[type="file"]');
    const name = picker.querySelector('[data-file-name]');
    if (!input) return;

    function updateName() {
      const files = [...(input.files || [])];
      if (!name) return;
      name.textContent = files.length === 0 ? (name.dataset.emptyLabel || '点击选择，或把文件拖到这里')
        : files.length === 1 ? files[0].name : `已选择 ${files.length} 个文件`;
    }

    input.addEventListener('change', updateName);
    picker.addEventListener('dragenter', (event) => {
      event.preventDefault();
      picker.classList.add('is-dragging');
    });
    picker.addEventListener('dragover', (event) => event.preventDefault());
    picker.addEventListener('dragleave', (event) => {
      if (!picker.contains(event.relatedTarget)) picker.classList.remove('is-dragging');
    });
    picker.addEventListener('drop', (event) => {
      event.preventDefault();
      picker.classList.remove('is-dragging');
      const files = event.dataTransfer?.files;
      if (!files?.length) return;
      // 统一写回 input.files，让拖放和点击选择走相同的 change/上传流程。
      const transfer = new DataTransfer();
      transfer.items.add(files[0]);
      input.files = transfer.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    updateName();
  });
})();
