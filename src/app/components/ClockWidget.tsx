import Head from "next/head";
import React, { useState, useEffect, useRef } from "react";

export function ClockWidget() {
  const [time, setTime] = useState<string>("");
  const intervalRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    const updateTime = () => {
      const now = new Date();
      const hours = now.getHours().toString().padStart(2, "0");
      const minutes = now.getMinutes().toString().padStart(2, "0");
      setTime(`${hours}:${minutes}`);
    };

    const scheduleNextUpdate = () => {
      const now = new Date();
      const msUntilNextMinute =
        (60 - now.getSeconds()) * 1000 - now.getMilliseconds();

      intervalRef.current = setTimeout(() => {
        updateTime();
        scheduleNextUpdate(); // Recursively schedule the next update
      }, msUntilNextMinute);
    };

    // Update immediately
    updateTime();

    // Schedule the first update at the next minute boundary
    scheduleNextUpdate();

    // Cleanup function
    return () => {
      if (intervalRef.current) {
        clearTimeout(intervalRef.current);
      }
    };
  }, []);

  return (
    <>
      <Head>
        <link
          href={`https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500&display=swap`}
          rel="stylesheet"
        />
      </Head>
      <div
        style={{
          position: "fixed",
          top: "20px",
          right: "20px",
          background: "rgba(0, 0, 0, 0.3)",
          borderRadius: "12px",
          padding: "12px 16px",
          zIndex: 50,
          backdropFilter: "blur(10px)",
          boxShadow: "0 4px 12px rgba(0, 0, 0, 0.15)",
          fontFamily: "Inter, sans-serif",
          fontSize: 60,
          fontWeight: 500,
          color: "white",
          textShadow: "0 1px 3px rgba(0, 0, 0, 0.5)",
          minWidth: "100px",
          textAlign: "center",
        }}
      >
        {time}
      </div>
    </>
  );
}
