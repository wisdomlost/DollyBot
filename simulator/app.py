"""Run from the repository root: python -m simulator.app --port COM7."""

import argparse
import math
import queue
import threading
import time
import tkinter as tk
from tkinter import ttk

import serial
from serial.tools import list_ports

from .model import Simulator
from .protocol import Decoder


class SerialWorker:
    def __init__(self, port):
        self.port = port
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
                next_status = time.monotonic()
                while not self.stop_event.is_set():
                    tick_started = time.monotonic()
                    now = time.monotonic()
                    model.advance(now)
                    received = connection.read(min(max(connection.in_waiting, 1), 4096))
                    for packet in decoder.feed(received):
                        for reply in model.handle(packet, time.monotonic()):
                            connection.write(reply.encode())
                    now = time.monotonic()
                    model.advance(now)
                    if now >= next_status:
                        next_status = max(next_status + 0.05, now)
                        if model.connected:
                            connection.write(model.status().encode())
                        self.publish({
                            "pose": model.pose(),
                            "connected": model.connected,
                            "calibrated": model.calibrated,
                            "mode": model.mode,
                            "teach_id": model.teach_id,
                            "interrupted": model.interrupted,
                            "continuous": tuple(model.histories[1]),
                            "waypoints": tuple(model.histories[2]),
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
    def __init__(self, root, initial_port):
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
            self.open_button.configure(text="打开串口")
            self.status.set("串口已关闭。")
        elif self.port.get().strip():
            self.worker = SerialWorker(self.port.get().strip())
            self.open_button.configure(text="关闭串口")
            self.status.set("串口已打开，等待手机 HELLO 握手。")

    def poll(self):
        if self.worker:
            if self.worker.error:
                self.status.set("串口错误：" + self.worker.error)
                self.worker.close()
                self.worker = None
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
        mode = {0: "空闲", 1: "连续录制", 2: "关键点取点"}[state["mode"]]
        link = "手机已连接" if state["connected"] else "等待手机连接"
        calibration = "已校准" if state["calibrated"] else "未校准"
        interruption = " · 上次示教已中断" if state["interrupted"] else ""
        self.status.set(f"{self.port.get()} · {link} · {calibration} · {mode}{interruption}")
        self.details.set(
            f"位置 ({pose.x}, {pose.y}) mm    车身 {pose.yaw / 100:.1f}°    "
            f"云台 {pose.gimbal_yaw / 100:.1f}° / {pose.gimbal_pitch / 100:.1f}°\n"
            f"连续采样 {len(state['continuous'])}    关键点 {len(state['waypoints'])}    "
            f"CRC/长度错误 {state['errors']}"
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
        all_points = list(state["continuous"]) + list(state["waypoints"]) + [state["pose"]]
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
        for key, color in (("continuous", "#61d4df"), ("waypoints", "#ffcf7b")):
            points = state[key]
            coords = [value for point in points for value in screen(point.x, point.y)]
            if len(points) > 1:
                canvas.create_line(*coords, fill=color, width=2)
            if key == "waypoints":
                for index, point in enumerate(points, 1):
                    x, y = screen(point.x, point.y)
                    canvas.create_oval(x - 9, y - 9, x + 9, y + 9, fill=color, outline="")
                    canvas.create_text(x, y, text=str(index), fill="#17232a")
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
    parser.add_argument("--port", default="", help="Windows Bluetooth incoming COM port, e.g. COM7")
    args = parser.parse_args()
    root = tk.Tk()
    SimulatorWindow(root, args.port)
    root.mainloop()


if __name__ == "__main__":
    main()
