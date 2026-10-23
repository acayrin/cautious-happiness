package com.overlaytranslator

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * Simple editor for the chinese→english term glossary.
 *
 * Stored as `terms` in the `glossary` SharedPreferences, one `chinese\tenglish`
 * per line, so it's trivially reloadable by CaptureService.
 */
class GlossaryActivity : AppCompatActivity() {

    private val rows = mutableListOf<Row>()
    private lateinit var adapter: RowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_glossary)

        rows.addAll(loadRows())
        adapter = RowAdapter(rows)

        val list = findViewById<RecyclerView>(R.id.glossaryList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.addTermButton).setOnClickListener {
            rows.add(Row("", ""))
            adapter.notifyItemInserted(rows.size - 1)
            list.scrollToPosition(rows.size - 1)
        }

        findViewById<MaterialButton>(R.id.saveGlossaryButton).setOnClickListener {
            saveRows()
            Toast.makeText(this, R.string.glossary_save, Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<TextView>(R.id.glossaryTitle).setOnClickListener { finish() }
    }

    private fun loadRows(): List<Row> {
        val raw = getSharedPreferences("glossary", Context.MODE_PRIVATE)
            .getString("terms", "") ?: ""
        return raw.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                Row(line.substring(0, tab).trim(), line.substring(tab + 1).trim())
            }
            .toList()
    }

    private fun saveRows() {
        val text = rows
            .filter { it.zh.isNotBlank() && it.en.isNotBlank() }
            .joinToString("\n") { "${it.zh}\t${it.en}" }
        getSharedPreferences("glossary", Context.MODE_PRIVATE)
            .edit()
            .putString("terms", text)
            .apply()
    }

    data class Row(var zh: String, var en: String)

    private class RowAdapter(
        private val rows: MutableList<Row>,
    ) : RecyclerView.Adapter<RowAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val zh: EditText = view.findViewById(R.id.termChinese)
            val en: EditText = view.findViewById(R.id.termEnglish)
            val del: ImageButton = view.findViewById(R.id.deleteButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_glossary, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.zh.setText(row.zh)
            holder.en.setText(row.en)

            holder.zh.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) row.zh = holder.zh.text.toString()
            }
            holder.en.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) row.en = holder.en.text.toString()
            }

            holder.del.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    rows.removeAt(pos)
                    notifyItemRemoved(pos)
                }
            }
        }
    }
}
