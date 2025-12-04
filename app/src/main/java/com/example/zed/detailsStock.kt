package com.example.zed

import android.graphics.Color
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarData

class detailsStock : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_details_stock, container, false)

        val barChart = view.findViewById<BarChart>(R.id.barChart)

        val imageView: ImageView = view.findViewById(R.id.stockImage)
        imageView.setImageResource(R.drawable.ic_placeholder)



        // Sample data
        val entries = listOf(
            BarEntry(0f, 100f),  // Yoyo
            BarEntry(1f, 150f),  // Castle Lite
            BarEntry(2f, 125f),  // Monarch
            BarEntry(3f, 180f)   // Simba
        )

        val dataSet = BarDataSet(entries, "Stock Levels")
        dataSet.colors = listOf(
            Color.parseColor("#004c91"), // Yoyo
            Color.parseColor("#00c3ff"), // Castle Lite
            Color.parseColor("#4a90e2"), // Monarch
            Color.parseColor("#0071bc")  // Simba
        )
        dataSet.valueTextColor = Color.parseColor("#0071c1") // ✅ numbers in blue
        dataSet.valueTextSize = 14f

        val barData = BarData(dataSet)
        barChart.data = barData

// Customize chart
        val labels = listOf("Yoyo", "Castle Lite", "Monarch", "Simba")
        barChart.xAxis.valueFormatter = com.github.mikephil.charting.formatter.IndexAxisValueFormatter(labels)
        barChart.xAxis.granularity = 1f
        barChart.xAxis.position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM // ✅ labels at bottom
        barChart.xAxis.setDrawGridLines(false)

        barChart.axisLeft.setDrawGridLines(false)
        barChart.axisRight.isEnabled = false
        barChart.description.isEnabled = false
        barChart.legend.isEnabled = false
        barChart.animateY(1000)


        return view
    }
}
