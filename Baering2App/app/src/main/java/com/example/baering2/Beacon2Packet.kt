package com.example.baering2

/**
 * 배달링2 비콘 광고 해석.
 *
 * 광고 원본 예: `09 09 62 61 65 72 69 6E 67 32 05 FF 00 00 55 DD`
 *  - `09 09 "baering2"` : Complete Local Name
 *  - `05 FF 00 00 55 DD`: 제조사 데이터, 회사 ID 0x0000(리틀 엔디언 `00 00`) + 데이터 2바이트
 *    - 바이트0 = VCC (1바이트)
 *    - 바이트1 = SPST 스위치 GPIO 상태 (1바이트, 비트마다 스위치 1개)
 *
 * 안드로이드: `scanRecord.getManufacturerSpecificData(0x0000)` → `[VCC, GPIO]`
 */
data class Beacon2Packet(val companyId: Int, val vcc: Int, val gpio: Int, val raw: ByteArray) {

    val gpioHex: String get() = hex2(gpio)
    val vccHex: String get() = hex2(vcc)

    companion object {
        const val TARGET_NAME = "baering2"
        const val COMPANY_ID = 0x0000
        const val SIZE = 2

        fun parse(companyId: Int, data: ByteArray?): Beacon2Packet? {
            if (data == null || data.size < SIZE) return null
            return Beacon2Packet(companyId, data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, data)
        }

        fun hex2(v: Int) = "0x%02X".format(v)

        /** 0xDD → "1101 1101" (bit7 … bit0) */
        fun bits(v: Int): String = (7 downTo 0).joinToString("") { b ->
            (if (v shr b and 1 == 1) "1" else "0") + if (b == 4) " " else ""
        }

        fun hexBytes(b: ByteArray?): String = b?.joinToString("-") { "%02X".format(it) } ?: ""
    }
}
