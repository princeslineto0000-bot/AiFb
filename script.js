let mediaRecorder;
let audioChunks = [];
let isRecording = false;
let latestSpeakText = "";

// Backend Java phải cung cấp POST /api/analyze, nhận multipart field "audio".
const AI_API_URL = "/api/analyze";

function showMessage(message) {
  const feedback = document.getElementById("aiFeedback");
  feedback.replaceChildren();
  const paragraph = document.createElement("p");
  paragraph.textContent = message;
  feedback.append(paragraph);
}

function addResultSection(container, title, text) {
  if (!text) return;
  const section = document.createElement("section");
  section.className = "result-section";
  const heading = document.createElement("h3");
  heading.textContent = title;
  const paragraph = document.createElement("p");
  paragraph.textContent = text;
  section.append(heading, paragraph);
  container.append(section);
}

function displayResult(result) {
  const feedback = document.getElementById("aiFeedback");
  const corrected = result.correctedText || result.correction || "";
  const transcript = result.transcript || result.transcription || "";
  const advice = result.feedback || result.tips || "";

  feedback.replaceChildren();
  addResultSection(feedback, "Bạn đã nói", transcript);
  addResultSection(feedback, "Phiên bản gợi ý", corrected);
  addResultSection(feedback, "Nhận xét", advice);

  if (!transcript && !corrected && !advice) {
    showMessage("API chưa trả về nội dung. Hãy kiểm tra response của /api/analyze.");
  }
  latestSpeakText = corrected || advice || transcript;
}

async function convertToWav(blob) {
  const AudioContextClass = window.AudioContext || window.webkitAudioContext;
  if (!AudioContextClass) throw new Error("Trình duyệt không hỗ trợ xử lý âm thanh.");

  const audioContext = new AudioContextClass();
  try {
    const decoded = await audioContext.decodeAudioData(await blob.arrayBuffer());
    const targetRate = 16000;
    const outputLength = Math.ceil(decoded.duration * targetRate);
    const mono = new Float32Array(outputLength);
    const channels = Array.from({ length: decoded.numberOfChannels }, (_, i) => decoded.getChannelData(i));

    for (let i = 0; i < outputLength; i++) {
      const start = Math.floor(i * decoded.sampleRate / targetRate);
      const end = Math.min(Math.floor((i + 1) * decoded.sampleRate / targetRate), decoded.length);
      let sample = 0;
      for (const channel of channels) {
        for (let j = start; j < Math.max(start + 1, end); j++) sample += channel[Math.min(j, decoded.length - 1)] / channels.length;
      }
      mono[i] = sample / Math.max(1, end - start);
    }

    const wav = new ArrayBuffer(44 + mono.length * 2);
    const view = new DataView(wav);
    const writeText = (offset, text) => [...text].forEach((char, i) => view.setUint8(offset + i, char.charCodeAt(0)));
    writeText(0, "RIFF");
    view.setUint32(4, 36 + mono.length * 2, true);
    writeText(8, "WAVE");
    writeText(12, "fmt ");
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, targetRate, true);
    view.setUint32(28, targetRate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    writeText(36, "data");
    view.setUint32(40, mono.length * 2, true);
    for (let i = 0; i < mono.length; i++) {
      const sample = Math.max(-1, Math.min(1, mono[i]));
      view.setInt16(44 + i * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
    }
    return new Blob([wav], { type: "audio/wav" });
  } finally {
    await audioContext.close();
  }
}

function speakFeedback() {
  const status = document.getElementById("status");
  if (!latestSpeakText || !("speechSynthesis" in window)) {
    status.textContent = "Trạng thái: Chưa có nội dung để đọc hoặc trình duyệt không hỗ trợ đọc văn bản.";
    return;
  }

  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(latestSpeakText);
  utterance.lang = "vi-VN";
  utterance.rate = 0.95;
  window.speechSynthesis.speak(utterance);
}

async function toggleRecording() {
  const recordBtn = document.getElementById("recordBtn");
  const status = document.getElementById("status");

  if (!isRecording) {
    try {
      if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
        throw new Error("Trình duyệt này không hỗ trợ ghi âm.");
      }

      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorder = new MediaRecorder(stream);
      audioChunks = [];

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) audioChunks.push(event.data);
      };

      mediaRecorder.onstop = async () => {
        const recordedBlob = new Blob(audioChunks, { type: mediaRecorder.mimeType || "audio/webm" });
        showMessage("Đang gửi bản ghi đến AI để chép lời và chỉnh sửa...");
        status.textContent = "Trạng thái: Đang xử lý bằng AI...";

        try {
          const audioBlob = await convertToWav(recordedBlob);
          const formData = new FormData();
          formData.append("audio", audioBlob, "recording.wav");
          const response = await fetch(AI_API_URL, { method: "POST", body: formData });
          if (!response.ok) {
            const detail = await response.text();
            throw new Error(`HTTP ${response.status}${detail ? `: ${detail}` : ""}`);
          }
          displayResult(await response.json());
          status.textContent = "Trạng thái: Đã nhận kết quả.";
        } catch (error) {
          console.error("Lỗi gửi âm thanh:", error);
          showMessage(`Không nhận được phản hồi từ Gemini: ${error.message || "hãy kiểm tra server và cấu hình API key."}`);
          status.textContent = "Trạng thái: Không nhận được phản hồi từ AI.";
        } finally {
          stream.getTracks().forEach((track) => track.stop());
        }
      };

      mediaRecorder.start();
      isRecording = true;
      recordBtn.innerHTML = '<span class="mic-icon" aria-hidden="true">⏹</span><span>Dừng ghi âm</span>';
      status.textContent = "Trạng thái: Đang ghi âm...";
    } catch (error) {
      console.error("Không thể bắt đầu ghi âm:", error);
      status.textContent = error.message || "Trạng thái: Không thể truy cập microphone.";
    }
  } else {
    mediaRecorder.stop();
    isRecording = false;
    recordBtn.innerHTML = '<span class="mic-icon" aria-hidden="true">🎤</span><span>Bắt đầu ghi âm</span>';
    status.textContent = "Trạng thái: Đã dừng ghi âm.";
  }
}
