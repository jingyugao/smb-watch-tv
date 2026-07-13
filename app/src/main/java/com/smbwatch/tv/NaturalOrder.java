package com.smbwatch.tv;

final class NaturalOrder {
    private NaturalOrder() { }

    static int compare(String left, String right) {
        if (left == null) return right == null ? 0 : -1;
        if (right == null) return 1;
        int a = 0;
        int b = 0;
        while (a < left.length() && b < right.length()) {
            char ca = left.charAt(a);
            char cb = right.charAt(b);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int endA = a;
                int endB = b;
                while (endA < left.length() && Character.isDigit(left.charAt(endA))) endA++;
                while (endB < right.length() && Character.isDigit(right.charAt(endB))) endB++;
                int significantA = a;
                int significantB = b;
                while (significantA < endA - 1 && left.charAt(significantA) == '0') significantA++;
                while (significantB < endB - 1 && right.charAt(significantB) == '0') significantB++;
                int digitsA = endA - significantA;
                int digitsB = endB - significantB;
                if (digitsA != digitsB) return Integer.compare(digitsA, digitsB);
                for (int i = 0; i < digitsA; i++) {
                    int cmp = Character.compare(left.charAt(significantA + i), right.charAt(significantB + i));
                    if (cmp != 0) return cmp;
                }
                int runLengthCompare = Integer.compare(endA - a, endB - b);
                if (runLengthCompare != 0) return runLengthCompare;
                a = endA;
                b = endB;
                continue;
            }
            int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (cmp != 0) return cmp;
            a++;
            b++;
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }
}
