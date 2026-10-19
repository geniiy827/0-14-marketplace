package com.geniiy.adder;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.math.BigDecimal;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText first = findViewById(R.id.first);
        EditText second = findViewById(R.id.second);
        TextView result = findViewById(R.id.result);
        Button add = findViewById(R.id.add);
        Button clear = findViewById(R.id.clear);

        add.setOnClickListener(v -> {
            try {
                BigDecimal a = parse(first.getText().toString());
                BigDecimal b = parse(second.getText().toString());
                BigDecimal sum = a.add(b).stripTrailingZeros();
                result.setText("= " + sum.toPlainString());
            } catch (NumberFormatException e) {
                result.setText("Введите числа");
            }
        });

        clear.setOnClickListener(v -> {
            first.setText("");
            second.setText("");
            result.setText("");
            first.requestFocus();
        });
    }

    private static BigDecimal parse(String s) {
        s = s.trim().replace(',', '.');
        if (s.isEmpty()) return BigDecimal.ZERO;
        return new BigDecimal(s);
    }
}
