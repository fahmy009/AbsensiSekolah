/**
 * FUNGSI LIST SISWA: Sinkronisasi 100% dengan tampilan Spreadsheet
 */
function apiGetSiswaAndroid(klsTarget) {
  try {
    const ss = SpreadsheetApp.openById(SS_ID);
    const sheetSiswa = ss.getSheetByName(S_SISWA);
    const sheetAbsen = ss.getSheetByName(S_ABSEN);
    
    // Ambil data mentah siswa (untuk NISN dan Nama)
    const sData = sheetSiswa.getDataRange().getValues();
    
    // Ambil data TAMPILAN absensi (untuk Jam yang persis sama dengan sel)
    const aDataDisplay = sheetAbsen.getDataRange().getDisplayValues();
    const aDataRaw = sheetAbsen.getDataRange().getValues(); // Untuk perbandingan tanggal
    
    const today = Utilities.formatDate(new Date(), "GMT+7", "yyyy-MM-dd");
    
    let map = {};
    // Loop melalui data absensi
    for (let i = 1; i < aDataRaw.length; i++) {
      if (!aDataRaw[i][0]) continue;
      
      // Ambil tanggal mentah untuk pengecekan hari ini
      let d = (aDataRaw[i][0] instanceof Date) 
              ? Utilities.formatDate(aDataRaw[i][0], "GMT+7", "yyyy-MM-dd") 
              : String(aDataRaw[i][0]).trim();
      
      if (d === today) {
        let nisnKey = String(aDataRaw[i][1]).replace(/'/g,"").trim();
        map[nisnKey] = { 
          s: String(aDataDisplay[i][7] || "-").trim(), // Status
          m: String(aDataDisplay[i][4] || "-").trim(), // Jam Masuk (Teks persis sel)
          p: String(aDataDisplay[i][5] || "-").trim(), // Jam Pulang (Teks persis sel)
          sh: String(aDataDisplay[i][8] || "-").trim(), // Jam Sholat (Teks persis sel)
          k: String(aDataDisplay[i][6] || "").trim()    // Keterangan
        };
      }
    }

    let res = [];
    let filter = String(klsTarget || '').toUpperCase();
    for (let i = 1; i < sData.length; i++) {
      if (!sData[i][1]) continue;
      
      let kSiswa = String(sData[i][8]).trim().toUpperCase();
      if (filter !== "" && filter !== "SEMUA" && kSiswa !== filter) continue;
      
      let nisn = String(sData[i][1]).replace(/'/g,"").trim();
      let det = map[nisn] || { s: "-", m: "-", p: "-", sh: "-", k: "" };
      
      res.push({ 
        nisn: nisn, 
        nama: String(sData[i][0]).trim(), 
        status: det.s, 
        jamMasuk: det.m, 
        jamPulang: det.p,
        jamSholat: det.sh,
        keterangan: det.k
      });
    }
    return { success: true, data: res };
  } catch (e) {
    return { success: false, message: "Sinkronisasi Gagal: " + e.toString() };
  }
}