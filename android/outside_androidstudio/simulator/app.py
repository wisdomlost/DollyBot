"""Run from outside_androidstudio: python -m simulator.app --port COM4."""

import argparse
import math
import queue
import threading
import time
import tkinter as tk
from tkinter import ttk

import serial
from serial.tools import list_ports

from .model import Simulator, StatusSchedule
from .protocol import Decoder


class SerialWorker:
    def __init__(self, port, status_interval_ms=50):
        self.port = port
        self.schedule = StatusSchedule(status_interval_ms)
        self.stop_event = threading.Event()
        self.updates = queue.Queue(maxsize=1)
        self.error = None
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def publish(self, value):
        try:
            self.updates.get_nowait()
        except queue.Empty:
            pass
        self.updates.put_nowait(value)

    def run(self):
        model = Simulator()
        decoder = Decoder()
        try:
            with serial.Serial(self.port, 115200, timeout=0.01, write_timeout=0.5) as connection:
                connection.reset_input_buffer()
                self.schedule.reset(time.monotonic())
                next_publish = time.monotonic()
                while not self.stop_event.is_set():
                    tick_started = time.monotonic()
                    now = time.monotonic()
                    model.advance(now)
                    received = connection.read(min(max(connection.in_waiting, 1), 4096))
                    for packet in decoder.feed(received):
                        print(
                            f"RX type=0x{packet.kind:02X} seq={packet.seq} session=0x{packet.session:08X} "
                            f"len={len(packet.payload)} frame={packet.encode().hex(' ').upper()}",
                            flush=True,
                        )
                        previous_teach_id = model.teach_id
                        for reply in model.handle(packet, time.monotonic()):
                            connection.write(reply.encode())
                        if model.teach_id != previous_teach_id:
                            self.schedule.reset(model.now)
                    now = time.monotonic()
                    model.advance(now)
                    if self.schedule.due(now):
                        if model.connected:
                            connection.write(model.status().encode())
                    if now >= next_publish:
                        next_publish = now + 0.05
                        self.publish({
                            "pose": model.pose(),
                            "connected": model.connected,
                            "calibrated": model.calibrated,
                            "mode": model.mode,
                            "teach_id": model.teach_id,
                            "interrupted": model.interrupted,
                            "continuous": tuple(model.histories[1]),
                            "waypoints": tuple(model.histories[2]),
                            "push": tuple(model.histories[3]),
                            "uploaded_trajectory": model.uploaded_trajectory,
                            "playback_state": model.playback_state,
                            "errors": decoder.errors,
                        })
                    self.stop_event.wait(max(0.0, 0.02 - (time.monotonic() - tick_started)))
        except (OSError, serial.SerialException) as error:
            self.error = str(error)
        finally:
            model.disconnect()

    def close(self):
        self.stop_event.set()


class SimulatorWindow:
    def __init__(self, root, initial_port, status_interval_ms=50):
        self.root = root
        self.worker = None
        self.snapshot = None
        root.title("DollyBot 蓝牙模拟车")
        root.geometry("1000x720")
        root.minsize(700, 520)
        root.protocol("WM_DELETE_WINDOW", self.close)
        toolbar = ttk.Frame(root, padding=10)
        toolbar.pack(fill="x")
        ttk.Label(toolbar, text="蓝牙传入串口").pack(side="left")
        self.port = tk.StringVar(value=initial_port)
        self.port_picker = ttk.Combobox(toolbar, textvariable=self.port, width=18)
        self.port_picker.pack(side="left", padx=8)
        ttk.Button(toolbar, text="刷新", command=self.refresh).pack(side="left")
        self.open_button = ttk.Button(toolbar, text="打开串口", command=self.toggle)
        self.open_button.pack(side="left", padx=8)
        ttk.Label(toolbar, text="上报周期 (ms)").pack(side="left", padx=(10, 4))
        self.interval = tk.StringVar(value=str(status_interval_ms))
        self.interval_picker = ttk.Spinbox(
            toolbar, from_=20, to=1000, increment=10, width=6, textvariable=self.interval
        )
        self.interval_picker.pack(side="left")
        self.status = tk.StringVar(value="选择 Windows 蓝牙“传入”COM 口，打开后从手机设备页连接。")
        ttk.Label(root, textvariable=self.status, padding=(10, 6), wraplength=900).pack(fill="x")
        self.canvas = tk.Canvas(root, background="#101b22", highlightthickness=0)
        self.canvas.pack(fill="both", expand=True, padx=10, pady=6)
        self.details = tk.StringVar(value="世界坐标 +X 向右、+Y 向上，车身 0° 指向 +Y。")
        ttk.Label(root, textvariable=self.details, padding=10, wraplength=900).pack(fill="x")
        self.canvas.bind("<Configure>", lambda event: self.draw())
        self.refresh()
        if initial_port:
            self.toggle()
        root.after(50, self.poll)

    def refresh(self):
        ports = [item.device for item in list_ports.comports()]
        self.port_picker["values"] = ports
        if not self.port.get() and ports:
            self.port.set(ports[0])

    def toggle(self):
        if self.worker:
            self.worker.close()
            self.worker.thread.join(timeout=1.0)
            if self.worker.thread.is_alive():
                self.status.set("正在关闭串口，请稍候。")
                return
            self.worker = None
            self.interval_picker.configure(state="normal")
            self.open_button.configure(text="打开串口")
            self.status.set("串口已关闭。")
        elif self.port.get().strip():
            try:
                self.worker = SerialWorker(self.port.get().strip(), int(self.interval.get()))
            except ValueError:
                self.status.set("上报周期必须为 20–1000 ms 的整数。")
                return
            self.interval_picker.configure(state="disabled")
            self.open_button.configure(text="关闭串口")
            self.status.set("串口已打开，等待手机 HELLO 握手。")

    def poll(self):
        if self.worker:
            if self.worker.error:
                self.status.set("串口错误：" + self.worker.error)
                self.worker.close()
                self.worker = None
                self.interval_picker.configure(state="normal")
                self.open_button.configure(text="打开串口")
            else:
                try:
                    self.snapshot = self.worker.updates.get_nowait()
                    self.update_labels()
                    self.draw()
                except queue.Empty:
                    pass
        self.root.after(50, self.poll)

    def update_labels(self):
        state = self.snapshot
        pose = state["pose"]
        mode = {0: "空闲", 1: "连续录制", 2: "关键点取点", 3: "推车录制"}[state["mode"]]
        if state["playback_state"] == "playing":
            mode = "轨迹执行"
        link = "手机已连接" if state["connected"] else "等待手机连接"
        calibration = "已校准" if state["calibrated"] else "未校准"
        interruption = " · 上次示教已中断" if state["interrupted"] else ""
        uploaded = state["uploaded_trajectory"]
        upload_detail = ""
        if uploaded:
            playback = {"saved": "已保存，等待启动", "playing": "执行中", "completed": "已完成", "stopped": "已停止"}[state["playback_state"]]
            upload_detail = f"\n最近收到的轨迹 {uploaded.name[:60]}（{len(uploaded.points)} 点） · {playback}"
        self.status.set(f"{self.port.get()} · {link} · {calibration} · {mode}{interruption}")
        self.details.set(
            f"位置 ({pose.x}, {pose.y}) mm    车身 {pose.yaw / 100:.1f}°    "
            f"云台 {pose.gimbal_yaw / 100:.1f}° / {pose.gimbal_pitch / 100:.1f}°\n"
            f"连续采样 {len(state['continuous'])}    关键点 {len(state['waypoints'])}    "
            f"推车采样 {len(state['push'])}    周期 {self.interval.get()} ms    "
            f"CRC/长度错误 {state['errors']}{upload_detail}"
        )

    def draw(self):
        canvas = self.canvas
        canvas.delete("all")
        width, height = canvas.winfo_width(), canvas.winfo_height()
        if width < 20 or height < 20:
            return
        if not self.snapshot:
            canvas.create_text(
                width / 2, height / 2, text="等待手机连接并校准", fill="#dceaf0",
                font=("Microsoft YaHei UI", 16)
            )
            return
        state = self.snapshot
        uploaded = state["uploaded_trajectory"]
        paths = (
            (state["continuous"], "#61d4df", False),
            (state["waypoints"], "#ffcf7b", True),
            (state["push"], "#bd99ff", False),
            (uploaded.points if uploaded else (), "#7be6a8", False),
        )
        all_points = [state["pose"]]
        for points, _, _ in paths:
            all_points.extend(points)
        xs = [point.x for point in all_points] + [0]
        ys = [point.y for point in all_points] + [0]
        cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
        span_x, span_y = max(400, max(xs) - min(xs) + 120), max(400, max(ys) - min(ys) + 120)
        scale = min((width - 70) / span_x, (height - 70) / span_y)

        def screen(x, y):
            return width / 2 + (x - cx) * scale, height / 2 - (y - cy) * scale

        step = 10 ** math.ceil(math.log10(max(span_x, span_y) / 10))
        for x in range(math.floor((cx - span_x) / step) * step, math.ceil(cx + span_x), step):
            px, _ = screen(x, 0)
            canvas.create_line(px, 0, px, height, fill="#273b47")
        for y in range(math.floor((cy - span_y) / step) * step, math.ceil(cy + span_y), step):
            _, py = screen(0, y)
            canvas.create_line(0, py, width, py, fill="#273b47")
        ox, oy = screen(0, 0)
        canvas.create_line(ox - 6, oy, ox + 6, oy, fill="#a7bcc7")
        canvas.create_line(ox, oy - 6, ox, oy + 6, fill="#a7bcc7")
        canvas.create_text(ox + 8, oy + 12, text="0", fill="#a7bcc7")
        for points, color, show_indices in paths:
            coords = [value for point in points for value in screen(point.x, point.y)]
            if len(points) > 1:
                canvas.create_line(*coords, fill=color, width=2)
            if show_indices:
                for index, point in enumerate(points, 1):
                    x, y = screen(point.x, point.y)
                    canvas.create_oval(x - 9, y - 9, x + 9, y + 9, fill=color, outline="")
                    canvas.create_text(x, y, text=str(index), fill="#17232a")
        if uploaded and len(uploaded.points) == 1:
            target = uploaded.points[0]
            tx, ty = screen(target.x, target.y)
            canvas.create_oval(tx - 6, ty - 6, tx + 6, ty + 6, outline="#7be6a8", width=2)
        pose = state["pose"]
        x, y = screen(pose.x, pose.y)
        angle = math.radians(pose.yaw / 100)
        canvas.create_oval(x - 9, y - 9, x + 9, y + 9, fill="#e8f5f7", outline="")
        canvas.create_line(
            x, y, x + math.sin(angle) * 35, y - math.cos(angle) * 35,
            fill="#e8f5f7", width=3, arrow="last"
        )
        canvas.create_text(16, 18, anchor="w", text=f"网格 {step} mm", fill="#b6cbd5")

    def close(self):
        if self.worker:
            self.worker.close()
        self.root.destroy()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default="", help="Windows Bluetooth incoming COM port, e.g. COM4")
    parser.add_argument("--status-interval-ms", type=int, default=50, help="周期上报间隔，20–1000 ms，默认 50")
    args = parser.parse_args()
    try:
        StatusSchedule(args.status_interval_ms)
    except ValueError as error:
        parser.error(str(error))
    root = tk.Tk()
    SimulatorWindow(root, args.port, args.status_interval_ms)
    root.mainloop()


if __name__ == "__main__":
    main()
